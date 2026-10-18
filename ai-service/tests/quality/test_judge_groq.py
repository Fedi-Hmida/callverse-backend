"""Juge qualité sur Groq (fournisseur par défaut), sans réseau : transport httpx simulé.

Constat du 2026-10-18 : avec qwen2.5:3b sous Ollama, sur CPU, une évaluation dépassait 5 minutes,
au-delà du délai de lecture du backend (130 s) ; aucune note n'était jamais enregistrée.
"""
import json

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.quality import judge
from app.quality.router import router
from .test_judge import request, result


def _use_groq(monkeypatch, handler, api_key="test-key"):
    original = httpx.Client
    monkeypatch.setattr(judge.settings, "quality_provider", "groq")
    monkeypatch.setattr(judge.settings, "quality_groq_model", "openai/gpt-oss-20b")
    monkeypatch.setattr(judge.settings, "llm_api_key", api_key)
    monkeypatch.setattr(judge.httpx, "Client",
                        lambda **kw: original(transport=httpx.MockTransport(handler), **kw))


def _groq_answer(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": content}}]})


def test_groq_est_le_fournisseur_par_defaut():
    assert judge.settings.__class__.model_fields["quality_provider"].default == "groq"


def test_groq_recoit_le_modele_la_cle_et_un_json_impose(monkeypatch):
    seen = {}

    def handler(req):
        seen["url"] = str(req.url)
        seen["auth"] = req.headers.get("Authorization")
        seen["body"] = json.loads(req.content)
        return _groq_answer(result().model_dump_json())

    _use_groq(monkeypatch, handler)

    assert judge.call_judge(request()).scores["compliance"] == 8
    assert seen["url"] == "https://api.groq.com/openai/v1/chat/completions"
    assert seen["auth"] == "Bearer test-key"
    assert seen["body"]["model"] == "openai/gpt-oss-20b"
    assert seen["body"]["response_format"] == {"type": "json_object"}
    assert seen["body"]["temperature"] == 0
    assert [m["role"] for m in seen["body"]["messages"]] == ["system", "user"]


def test_groq_sans_cle_refuse_sans_appel(monkeypatch):
    def handler(req):
        raise AssertionError("aucun appel sans clé")

    _use_groq(monkeypatch, handler, api_key="")

    with pytest.raises(judge.JudgeUnavailable, match="llm_api_key"):
        judge.call_judge(request())


@pytest.mark.parametrize("failure,expected_status,detail", [
    ("timeout", 504, "Groq"),
    ("connect", 503, "Groq"),
    ("quota", 503, "quota"),
    ("server_error", 503, "HTTP 500"),
    ("no_content", 502, "malformée"),
])
def test_erreurs_groq_distinguables(monkeypatch, failure, expected_status, detail):
    def handler(req):
        if failure == "timeout":
            raise httpx.ReadTimeout("slow", request=req)
        if failure == "connect":
            raise httpx.ConnectError("refused", request=req)
        if failure == "quota":
            return httpx.Response(429, json={"error": {"message": "rate limit"}})
        if failure == "no_content":
            return httpx.Response(200, json={"choices": []})
        return httpx.Response(500, json={"error": {"message": "provider detail"}})

    _use_groq(monkeypatch, handler)
    app = FastAPI()
    app.include_router(router, prefix="/ai/quality")

    response = TestClient(app).post("/ai/quality/evaluate", json=request().model_dump())

    assert response.status_code == expected_status
    assert detail in response.json()["detail"]
    assert "test-key" not in response.text


def test_fournisseur_inconnu_refuse(monkeypatch):
    monkeypatch.setattr(judge.settings, "quality_provider", "autre")
    with pytest.raises(judge.JudgeUnavailable, match="QUALITY_PROVIDER"):
        judge.call_judge(request())
