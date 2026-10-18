import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.config import Settings
from app.security import AIServiceKeyMiddleware, require_ai_service_key


def _client(api_key: str, allow_unauthenticated: bool = False) -> TestClient:
    app = FastAPI()
    app.add_middleware(
        AIServiceKeyMiddleware, ai_service_key=api_key, allow_unauthenticated=allow_unauthenticated
    )

    @app.get("/ai/protected")
    def protected():
        return {"status": "ok"}

    @app.get("/health")
    def health():
        return {"status": "ok"}

    return TestClient(app)


def test_ai_routes_require_configured_key_but_health_does_not():
    client = _client("fake-ai-service-key-for-tests")

    assert client.get("/ai/protected").status_code == 401
    assert client.get("/ai/protected", headers={"X-AI-Key": "wrong-test-key"}).status_code == 401
    assert client.get("/ai/protected", headers={"X-AI-Key": "fake-ai-service-key-for-tests"}).status_code == 200
    assert client.get("/health").status_code == 200


def test_an_empty_key_refuses_to_serve_instead_of_opening_the_ai_routes():
    with pytest.raises(ValueError, match="AI_SERVICE_KEY"):
        _client("").get("/ai/protected")


def test_startup_check_refuses_an_empty_key():
    for empty in ("", "   "):
        with pytest.raises(RuntimeError, match="AI_SERVICE_KEY"):
            require_ai_service_key(empty, auth_disabled=False)
    with pytest.raises(ValueError, match="AI_SERVICE_KEY"):
        _client("   ").get("/ai/protected")
    require_ai_service_key("fake-ai-service-key-for-tests", auth_disabled=False)


def test_only_an_explicit_dev_opt_out_serves_without_a_key():
    require_ai_service_key("", auth_disabled=True)

    assert _client("", allow_unauthenticated=True).get("/ai/protected").status_code == 200


def test_the_dev_opt_out_is_off_unless_set(monkeypatch):
    monkeypatch.delenv("AI_SERVICE_AUTH_DISABLED", raising=False)
    assert Settings(_env_file=None).ai_service_auth_disabled is False

    monkeypatch.setenv("AI_SERVICE_AUTH_DISABLED", "true")
    assert Settings(_env_file=None).ai_service_auth_disabled is True
