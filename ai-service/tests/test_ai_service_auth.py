from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.security import AIServiceKeyMiddleware


def _client(api_key: str) -> TestClient:
    app = FastAPI()
    app.add_middleware(AIServiceKeyMiddleware, ai_service_key=api_key)

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


def test_ai_routes_remain_compatible_when_key_is_unconfigured():
    response = _client("").get("/ai/protected")

    assert response.status_code == 200
