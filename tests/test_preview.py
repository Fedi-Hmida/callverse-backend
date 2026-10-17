import pytest
from fastapi.testclient import TestClient
from app.preview import app, scope, customer, blocked

def test_preview_requires_service_key(monkeypatch):
    monkeypatch.setenv("AI_PREVIEW_KEY", "test-local-key")
    client = TestClient(app)
    assert client.get("/health").status_code == 401
    assert client.get("/health", headers={"x-preview-key": "wrong"}).status_code == 401
    assert client.get("/health", headers={"x-preview-key": "test-local-key"}).status_code == 200

@pytest.mark.asyncio
async def test_preview_cannot_read_another_customer():
    token = scope.set({"customer_id": "allowed", "authorization": "Bearer test"})
    try:
        with pytest.raises(PermissionError):
            await customer("another")
    finally:
        scope.reset(token)

@pytest.mark.asyncio
async def test_preview_business_writes_are_blocked():
    with pytest.raises(PermissionError):
        await blocked(customer_id="demo", amount=100)
