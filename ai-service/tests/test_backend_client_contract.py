import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest

from app.advisor.nodes import execute_tool
from app.clients.backend_client import BackendClient
from app.config import Settings
from app.contracts.advisor import Mode


def _use_transport(monkeypatch, handler):
    transport = httpx.MockTransport(handler)
    original_async_client = httpx.AsyncClient
    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: original_async_client(transport=transport, **kwargs),
    )


@pytest.mark.asyncio
async def test_customer_transactions_and_incidents_use_internal_service_key(monkeypatch):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={})

    _use_transport(monkeypatch, handler)
    client = BackendClient("http://backend.test", service_key="fake-test-key")

    await client.get_customer("customer-1")
    await client.get_transactions("customer-1")
    await client.check_system_status("paiement_carte")
    await client.aclose()

    assert [request.url.path for request in requests] == [
        "/internal/customers/customer-1",
        "/internal/customers/customer-1/transactions",
        "/internal/service-incidents",
    ]
    assert requests[1].url.params["n"] == "3"
    assert requests[2].url.params["service"] == "CARD_PAYMENTS"
    assert all(request.headers["X-Internal-Key"] == "fake-test-key" for request in requests)


@pytest.mark.asyncio
async def test_unknown_service_is_explicit_and_does_not_call_backend(monkeypatch):
    requests = []
    _use_transport(
        monkeypatch,
        lambda request: requests.append(request) or httpx.Response(200, json={}),
    )
    client = BackendClient("http://backend.test", service_key="fake-test-key")

    with pytest.raises(ValueError, match="UNRECOGNIZED_SERVICE"):
        await client.check_system_status("unknown zone")

    assert requests == []


@pytest.mark.asyncio
async def test_ticket_payload_matches_internal_contract_and_credit_501_is_explicit(monkeypatch):
    requests = []

    def handler(request):
        requests.append(request)
        if request.url.path == "/internal/credits":
            return httpx.Response(
                501,
                json={"status": 501, "code": "NOT_IMPLEMENTED", "message": "Unavailable"},
            )
        return httpx.Response(201, json={"id": "ticket-1"})

    _use_transport(monkeypatch, handler)
    client = BackendClient("http://backend.test", service_key="fake-test-key")

    ticket = await client.create_case(
        "customer-1",
        "Carte refusée",
        conversation_id="conversation-1",
        incident_id="incident-1",
        category="CARD",
    )
    credit = await client.apply_credit("customer-1", 10.0)
    await client.aclose()

    assert ticket == {"id": "ticket-1"}
    assert json.loads(requests[0].content) == {
        "customerId": "customer-1",
        "conversationId": "conversation-1",
        "category": "CARD",
        "title": "Carte refusée",
        "description": "Incident lié : incident-1",
    }
    assert credit == {"code": "NOT_IMPLEMENTED", "available": False}


@pytest.mark.asyncio
async def test_execute_tool_injects_conversation_id_from_advisor_request(monkeypatch):
    implementation = AsyncMock(return_value={"id": "ticket-1"})
    monkeypatch.setattr(
        "app.advisor.nodes.TOOL_IMPLEMENTATIONS",
        {"create_case": implementation},
    )
    state = {
        "request": SimpleNamespace(
            conversation_id="trusted-conversation-id", customer_id="trusted-customer-id", mode=Mode.live
        ),
        "decision": {
            "action": "call_tool",
            "tool_name": "create_case",
            "arguments": {"customer_id": "customer-1", "subject": "Carte refusée"},
        },
    }

    result = await execute_tool(state)

    implementation.assert_awaited_once_with(
        customer_id="trusted-customer-id",
        subject="Carte refusée",
        conversation_id="trusted-conversation-id",
    )
    assert result["tool_calls"][0]["error"] is None


def test_settings_read_aligned_backend_url_and_service_key_environment_names(monkeypatch):
    monkeypatch.setenv("CALLVERSE_BACKEND_BASE_URL", "http://backend.test")
    monkeypatch.setenv("INTERNAL_SERVICE_KEY", "fake-test-key")

    configured = Settings(_env_file=None)

    assert configured.backend_base_url == "http://backend.test"
    assert configured.internal_service_key == "fake-test-key"
