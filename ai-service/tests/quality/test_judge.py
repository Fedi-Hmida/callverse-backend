"""Tests du pipeline avec un juge simulé, sans réseau ni modèle."""
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError
from app.contracts.quality import QualityRequest, JudgeResult, CODES
from app.quality import judge
from app.quality.router import router
from app.quality.validation import agreement, cohen_kappa, pearson

def request():
    return QualityRequest(conversation_id="test", messages=[
        {"sender": "CUSTOMER", "content": "Ma carte est perdue."},
        {"sender": "ADVISOR", "content": "Je comprends. Utilisez le canal sécurisé pour faire opposition."}],
        source_documents=[{"doc_id": "carte", "chunk_id": "1",
                           "content": "En cas de perte, utiliser le canal sécurisé pour faire opposition."}],
        applicable_rules=["En cas de perte, orienter vers le canal sécurisé."])

def result():
    return JudgeResult(scores={c: 8 for c in CODES}, evidence=[
        {"criterion": c, "score": 8, "message_index": 1, "evidence": "Je comprends.",
         "justification": "Explication du critère."} for c in CODES],
        claims=[{"message_index": 1, "excerpt": "Utilisez le canal sécurisé pour faire opposition.",
                 "status": "SUPPORTED", "source_keys": ["carte/1"],
                 "justification": "La procédure le confirme."}],
        procedure_violations=[], explanation="Conversation satisfaisante.", recommendations=[])

def test_pipeline_recomputes_score(monkeypatch):
    data = result()
    data.scores["compliance"] = 0
    next(e for e in data.evidence if e.criterion == "compliance").score = 0
    monkeypatch.setattr(judge, "call_judge", lambda _: data)
    output = judge.evaluate(request())
    assert output.global_score == 6
    assert output.flags.unsourced_claims == 0

@pytest.mark.parametrize("change", ["excerpt", "index", "negative_index", "source", "unsupported_tool"])
def test_rejects_invented_evidence(monkeypatch, change):
    data = result()
    if change == "excerpt":
        data.evidence[0].evidence = "Phrase inventée"
    elif change == "index":
        data.evidence[0].message_index = 99
    elif change == "negative_index":
        data.evidence[0].message_index = -1
    elif change == "source":
        data.claims[0].source_keys = ["inconnu/1"]
    else:
        data.claims[0].source_keys = []
    monkeypatch.setattr(judge, "call_judge", lambda _: data)
    with pytest.raises(judge.InvalidJudgeOutput):
        judge.evaluate(request())

def test_unverifiable_is_not_unsourced(monkeypatch):
    data = result()
    data.claims[0].status = "UNVERIFIABLE"
    data.claims[0].source_keys = []
    monkeypatch.setattr(judge, "call_judge", lambda _: data)
    output = judge.evaluate(request())
    assert output.flags.unverifiable_claims == 1
    assert output.flags.unsourced_claims == 0

def test_missing_criterion_rejected():
    data = result().model_dump()
    del data["scores"]["empathy"]
    with pytest.raises(ValidationError):
        JudgeResult.model_validate(data)

def test_invalid_request_and_unavailable_judge(monkeypatch):
    app = FastAPI()
    app.include_router(router, prefix="/ai/quality")
    client = TestClient(app)
    assert client.post("/ai/quality/evaluate", json={}).status_code == 422
    def unavailable(_):
        raise judge.JudgeUnavailable("Indisponible")
    monkeypatch.setattr(judge, "call_judge", unavailable)
    assert client.post("/ai/quality/evaluate", json=request().model_dump()).status_code == 503

def test_ollama_schema_transport(monkeypatch):
    import httpx
    def handler(req):
        import json
        body = json.loads(req.content)
        assert body["stream"] is False
        assert body["format"] == JudgeResult.model_json_schema()
        return httpx.Response(200, json={"message": {"content": result().model_dump_json()}})
    original = httpx.Client
    monkeypatch.setattr(judge.settings, "quality_model", "test-model")
    monkeypatch.setattr(judge.httpx, "Client",
                        lambda **kw: original(transport=httpx.MockTransport(handler), **kw))
    assert judge.call_judge(request()).scores["compliance"] == 8

def test_metrics():
    assert cohen_kappa([0, 1, 2], [0, 1, 2]) == 1
    assert pearson([1, 2, 3], [2, 4, 6]) == pytest.approx(1)
    assert pearson([1, 1], [1, 1]) is None
    assert cohen_kappa([1, 1], [1, 1]) is None
    rows = [{c: i for c in CODES} for i in [2, 5, 8]]
    report = agreement(rows, rows)
    assert report["mae_global"] == 0
    assert report["kappa_by_criterion"]["compliance"] == 1


@pytest.mark.parametrize("failure,expected_status,detail", [
    ("timeout", 504, "600"),
    ("connect", 503, "Connexion"),
    ("missing_model", 503, "introuvable"),
    ("server_error", 503, "HTTP 500"),
])
def test_ollama_errors_are_distinguishable(monkeypatch, failure, expected_status, detail):
    import httpx
    def handler(req):
        if failure == "timeout":
            raise httpx.ReadTimeout("slow model", request=req)
        if failure == "connect":
            raise httpx.ConnectError("connection refused", request=req)
        return httpx.Response(404 if failure == "missing_model" else 500,
                              json={"error": "provider detail"})
    original = httpx.Client
    monkeypatch.setattr(judge.settings, "quality_model", "test-model")
    monkeypatch.setattr(judge.settings, "quality_timeout_seconds", 600.)
    monkeypatch.setattr(judge.httpx, "Client",
                        lambda **kw: original(transport=httpx.MockTransport(handler), **kw))
    app = FastAPI()
    app.include_router(router, prefix="/ai/quality")
    response = TestClient(app).post("/ai/quality/evaluate", json=request().model_dump())
    assert response.status_code == expected_status
    assert detail in response.json()["detail"]
