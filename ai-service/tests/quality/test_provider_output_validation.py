"""Malformed model/provider payloads get a controlled error, never an unhandled 500."""
import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.quality import judge
from app.quality.router import router
from .test_judge import request, result


@pytest.mark.parametrize("provider", ["groq", "ollama"])
@pytest.mark.parametrize("content", [None, {}, "", "   "])
def test_non_text_or_empty_provider_content_is_rejected(monkeypatch, provider, content):
    original = httpx.Client
    body = {"choices": [{"message": {"content": content}}]} if provider == "groq" else {"message": {"content": content}}
    monkeypatch.setattr(judge.settings, "quality_provider", provider)
    monkeypatch.setattr(judge.settings, "quality_model", "test-model")
    monkeypatch.setattr(judge.settings, "llm_api_key", "test-key")
    monkeypatch.setattr(judge.httpx, "Client", lambda **kw: original(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=body)), **kw))
    app = FastAPI()
    app.include_router(router, prefix="/ai/quality")

    response = TestClient(app).post("/ai/quality/evaluate", json=request().model_dump())

    assert response.status_code == 502


@pytest.mark.parametrize("provider", ["groq", "ollama"])
def test_provider_success_with_invalid_json_is_rejected(monkeypatch, provider):
    original = httpx.Client
    monkeypatch.setattr(judge.settings, "quality_provider", provider)
    monkeypatch.setattr(judge.settings, "quality_model", "test-model")
    monkeypatch.setattr(judge.settings, "llm_api_key", "test-key")
    monkeypatch.setattr(judge.httpx, "Client", lambda **kw: original(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, text="unavailable")), **kw))

    with pytest.raises(judge.InvalidJudgeOutput):
        judge.call_judge(request())


def test_non_finite_grade_is_invalid_model_output(monkeypatch):
    monkeypatch.setattr(judge.settings, "quality_provider", "groq")
    monkeypatch.setattr(judge.settings, "llm_api_key", "test-key")
    raw = result().model_dump_json().replace('"compliance":8', '"compliance":1e309', 1)
    monkeypatch.setattr(judge, "_ask_groq", lambda _: raw)

    with pytest.raises(judge.InvalidJudgeOutput):
        judge.call_judge(request())


def test_malformed_provider_error_envelope_still_has_controlled_failure(monkeypatch):
    original = httpx.Client
    monkeypatch.setattr(judge.settings, "quality_provider", "groq")
    monkeypatch.setattr(judge.settings, "llm_api_key", "test-key")
    monkeypatch.setattr(judge.httpx, "Client", lambda **kw: original(
        transport=httpx.MockTransport(lambda _: httpx.Response(400, json={"error": []})), **kw))

    with pytest.raises(judge.JudgeUnavailable):
        judge.call_judge(request())
