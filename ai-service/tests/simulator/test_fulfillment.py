"""Actual graph/API regressions; only the provider's wording/assessment is substituted."""
import json
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest
import httpx
from groq import RateLimitError

from app.simulator import graph
from app.simulator.router import router
from app.simulator.assessment import parse_turn


WEATHER = "La temperature sera de 22 degres demain, je vous souhaite une bonne journee."
FEE_ANSWER = "Ces frais de tenue de compte de 12 euros sont preleves chaque mois selon votre contrat."


def _history(reply=FEE_ANSWER):
    return [{"role":"customer", "content":"Pourquoi ces frais ?", "ts":"2026-10-18T10:00:00Z"},
            {"role":"advisor", "content":reply, "ts":"2026-10-18T10:00:01Z"}]


def _payload(**changes):
    payload = {"conversation_id":"11111111-1111-1111-1111-111111111111", "history":_history(),
        "state":{"profile":"calme", "patience":80, "satisfaction":50,
                 "objective":"comprendre les frais", "objective_met":False}}
    payload.update(changes)
    return payload


@pytest.fixture
def service(monkeypatch):
    calls = []
    response = {"content":"Merci, pouvez-vous preciser votre reponse ?", "objective_kind":"informational",
                "objective_met":False, "evidence_quote":""}

    def invoke(prompt):
        calls.append(prompt)
        return SimpleNamespace(content=json.dumps(response))

    monkeypatch.setattr(graph, "_get_llm", lambda **kwargs: SimpleNamespace(invoke=invoke))
    app = FastAPI()
    app.include_router(router, prefix="/ai/simulator")
    with TestClient(app) as client:
        yield client, response, calls


def _post(service, payload):
    result = service[0].post("/ai/simulator/next-message", json=payload)
    assert result.status_code == 200, result.text
    return result.json()


def test_unrelated_numeric_reply_cannot_complete_lost_card_even_with_high_satisfaction(service):
    payload = _payload(history=_history(WEATHER))
    payload["state"].update(objective="faire opposition sur une carte perdue", satisfaction=95)
    service[1].update(objective_met=True, evidence_quote=WEATHER)
    result = _post(service, payload)
    assert result["status"] == "en_cours"
    assert result["state"]["objective_met"] is False
    assert result["state"]["satisfaction"] < 95


def test_repeated_unrelated_answers_never_resolve_the_operation(service):
    payload = _payload(history=_history(WEATHER))
    payload["state"]["objective"] = "faire opposition sur une carte perdue"
    for _ in range(5):
        result = _post(service, payload)
        assert result["state"]["objective_met"] is False
        assert result["status"] != "resolu"
        payload["state"] = result["state"]


@pytest.mark.parametrize("objective", [
    "bloquer une carte perdue", "faire opposition sur une carte", "obtenir un remboursement",
    "cloturer mon compte", "effectuer un virement", "comprendre les frais et obtenir un remboursement",
    "Quels sont les frais et bloquez ma carte", "savoir les frais puis faire un virement",
])
def test_prose_cannot_verify_an_operational_or_compound_objective(service, objective):
    reply = "Votre carte est bloquee et le remboursement de 12 euros a ete effectue sur votre compte."
    payload = _payload(history=_history(reply))
    payload["state"].update(objective=objective, satisfaction=99)
    # Even a model that wrongly labels the operation informational is refused locally.
    service[1].update(objective_met=True, evidence_quote=reply)
    assert _post(service, payload)["state"]["objective_met"] is False


@pytest.mark.parametrize("objective,answer", [
    ("comprendre les frais", FEE_ANSWER),
    ("Quels documents sont necessaires pour cloturer un compte ?",
     "Pour cloturer votre compte, les documents requis sont une piece d'identite et un justificatif de domicile recent."),
])
def test_informational_answer_resolves_with_same_call_and_exact_latest_evidence(service, objective, answer):
    payload = _payload(history=_history(answer))
    payload["state"]["objective"] = objective
    service[1].update(objective_met=True, evidence_quote=answer)
    result = _post(service, payload)
    assert result["status"] == "resolu"
    assert result["state"]["objective_met"] is True
    assert result["content"] is None
    assert len(service[2]) == 1


@pytest.mark.parametrize("answer,quote", [
    (WEATHER, WEATHER),
    ("Je ne peux pas expliquer les frais de votre compte, je transmets votre demande au service competent.",
     "Je ne peux pas expliquer les frais de votre compte, je transmets votre demande au service competent."),
    ("Je vais expliquer les frais de votre compte des que le service aura verifie votre dossier.",
     "Je vais expliquer les frais de votre compte des que le service aura verifie votre dossier."),
    ("Si vous demandez la cloture, les frais de compte pourraient changer selon le futur contrat.",
     "Si vous demandez la cloture, les frais de compte pourraient changer selon le futur contrat."),
    ("Pouvez-vous me dire pourquoi vous avez des frais de tenue de compte de 12 euros ?",
     "Pouvez-vous me dire pourquoi vous avez des frais de tenue de compte de 12 euros ?"),
    (FEE_ANSWER, FEE_ANSWER.replace("12", "99")),
])
def test_wrong_irrelevant_or_unanswered_evidence_does_not_resolve_information(service, answer, quote):
    service[1].update(objective_met=True, evidence_quote=quote)
    payload = _payload(history=_history(answer))
    payload["state"]["satisfaction"] = 99
    assert _post(service, payload)["state"]["objective_met"] is False


def test_quote_from_old_advisor_answer_cannot_verify_latest_answer(service):
    payload = _payload(history=_history() + _history("Je ne sais pas, je dois verifier votre demande."))
    service[1].update(objective_met=True, evidence_quote=FEE_ANSWER)
    assert _post(service, payload)["state"]["objective_met"] is False


def test_customer_quote_cannot_be_used_as_advisor_evidence(service):
    history = _history()
    history[0]["content"] = FEE_ANSWER
    history[1]["content"] = "Je vous remercie, je vais verifier votre dossier."
    service[1].update(objective_met=True, evidence_quote=FEE_ANSWER)
    assert _post(service, _payload(history=history))["state"]["objective_met"] is False


def test_trusted_operational_completion_is_preserved_without_another_provider_call(service):
    payload = _payload(history=[])
    payload["state"].update(objective="bloquer une carte perdue", objective_met=True, satisfaction=10, patience=0)
    result = _post(service, payload)
    assert result["status"] == "resolu"
    assert result["state"]["objective_met"] is True
    assert result["content"] is None
    assert service[2] == []


def test_high_satisfaction_without_an_answer_does_not_complete_information(service):
    payload = _payload(history=[])
    payload["state"]["satisfaction"] = 99
    service[1].update(objective_met=True, evidence_quote=FEE_ANSWER)
    assert _post(service, payload)["state"]["objective_met"] is False


def test_wait_is_applied_even_before_the_first_advisor_reply(service):
    payload = _payload(history=[], wait_seconds=600)
    payload["state"]["profile"] = "impatient"
    result = _post(service, payload)
    assert result["state"]["patience"] == 40
    assert result["status"] == "en_cours"


def test_long_wait_abandons_without_a_provider_call(service):
    payload = _payload(history=[], wait_seconds=3600)
    payload["state"]["profile"] = "impatient"
    result = _post(service, payload)
    assert result["state"]["patience"] == 0
    assert result["status"] == "abandonne"
    assert result["content"] is None
    assert service[2] == []


def test_standalone_advisor_reply_is_evaluated_and_history_is_not_duplicated(service):
    payload = _payload(history=_history()[:1], last_advisor_reply=FEE_ANSWER)
    result = _post(service, payload)
    assert result["state"]["satisfaction"] == 58
    assert result["state"]["patience"] == 78.5
    assert service[2][0].count(FEE_ANSWER) == 1

    service[2].clear()
    _post(service, _payload(last_advisor_reply="  " + FEE_ANSWER + "  "))
    assert service[2][0].count(FEE_ANSWER) == 1


def test_repeated_text_after_a_new_customer_message_is_a_new_reply(service):
    payload = _payload(history=_history() + _history()[:1], last_advisor_reply=FEE_ANSWER)
    result = _post(service, payload)
    assert result["state"]["satisfaction"] == 58
    assert service[2][0].count(FEE_ANSWER) == 2


def test_string_truthiness_cannot_confirm_a_trusted_objective(service):
    payload = _payload()
    payload["state"]["objective_met"] = "false"
    response = service[0].post("/ai/simulator/next-message", json=payload)
    assert response.status_code == 422


@pytest.mark.parametrize("raw", [
    '{"content":"Merci","objective_kind":"informational","objective_met":"false","evidence_quote":"abc"}',
    '{"content":"Merci","objective_met":true}',
    "Une reponse libre ne constitue pas une preuve.",
])
def test_unstructured_or_invalid_assessment_is_not_completion_evidence(raw):
    assert parse_turn(raw) is None


def test_provider_rate_limit_preserves_retry_after_without_leaking_provider_body(service, monkeypatch):
    error = RateLimitError("private provider account details", response=httpx.Response(429,
        headers={"Retry-After":"3"}, request=httpx.Request("POST","https://provider.invalid/chat")), body={})

    def unavailable(**kwargs):
        raise error

    monkeypatch.setattr(graph, "_get_llm", unavailable)
    response = service[0].post("/ai/simulator/next-message", json=_payload())
    assert response.status_code == 429
    assert response.headers["Retry-After"] == "3"
    assert "private" not in response.text
