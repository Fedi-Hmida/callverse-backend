import httpx
import asyncio
import pytest
from unittest.mock import AsyncMock

from groq import RateLimitError
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import provider_retry
from app.advisor import nodes, router
from app.config import settings
from app.contracts.advisor import AdvisorRequest


def limited(retry_after="2"):
    return RateLimitError("private provider account details", response=httpx.Response(
        429, headers={"retry-after": retry_after}, request=httpx.Request("POST", "https://provider.test")), body={})


@pytest.fixture(autouse=True)
def clear_cooldowns(monkeypatch):
    monkeypatch.setattr(provider_retry, "_cooldowns", {})


@pytest.mark.asyncio
async def test_retries_the_provider_call_after_its_cooldown_without_rerunning_tools(monkeypatch):
    sleep = AsyncMock()
    monkeypatch.setattr(provider_retry.asyncio, "sleep", sleep)
    llm = AsyncMock()
    llm.ainvoke.side_effect = [limited("2.1"), "answer"]
    result = await provider_retry.invoke_with_retry(llm, ["message"], model="test")
    assert result == "answer"
    assert llm.ainvoke.await_count == 2
    assert sleep.await_count == 1
    assert 0 < sleep.await_args.args[0] <= 2.1


@pytest.mark.asyncio
async def test_long_quota_window_is_returned_to_caller_instead_of_busy_retrying():
    llm = AsyncMock()
    llm.ainvoke.side_effect = limited("120")
    with pytest.raises(provider_retry.ProviderRateLimited) as caught:
        await provider_retry.invoke_with_retry(llm, [], model="test")
    assert caught.value.retry_after == 120
    assert llm.ainvoke.await_count == 1


@pytest.mark.asyncio
async def test_another_request_extending_the_cooldown_is_respected_before_invocation(monkeypatch):
    monkeypatch.setattr(provider_retry.time, "monotonic", lambda: 100.0)
    provider_retry._cooldowns["test"] = 102.0

    async def extend_cooldown(_delay):
        provider_retry._cooldowns["test"] = 220.0

    monkeypatch.setattr(provider_retry.asyncio, "sleep", extend_cooldown)
    llm = AsyncMock()
    with pytest.raises(provider_retry.ProviderRateLimited) as caught:
        await provider_retry.invoke_with_retry(llm, [], model="test")

    assert caught.value.retry_after == 120
    llm.ainvoke.assert_not_awaited()


@pytest.mark.asyncio
async def test_retries_are_bounded_and_authentication_or_tool_errors_are_not_retried(monkeypatch):
    monkeypatch.setattr(provider_retry.asyncio, "sleep", AsyncMock())
    llm = AsyncMock()
    llm.ainvoke.side_effect = limited("1")
    with pytest.raises(provider_retry.ProviderRateLimited):
        await provider_retry.invoke_with_retry(llm, [], model="test", max_retries=2)
    assert llm.ainvoke.await_count == 3
    broken = AsyncMock()
    broken.ainvoke.side_effect = ValueError("not a quota failure")
    with pytest.raises(ValueError):
        await provider_retry.invoke_with_retry(broken, [], model="other")
    assert broken.ainvoke.await_count == 1


def test_quota_response_keeps_retry_after_and_hides_provider_account(monkeypatch):
    async def run(_request):
        raise provider_retry.ProviderRateLimited(17)
    monkeypatch.setattr(router, "run_advisor", run)
    app = FastAPI()
    app.include_router(router.router, prefix="/ai/advisor")
    response = TestClient(app).post("/ai/advisor/respond", json={
        "conversation_id": "11111111-1111-1111-1111-111111111111",
        "customer_id": "22222222-2222-2222-2222-222222222222",
        "message": "Bonjour", "mode": "LIVE", "context": {"tenure_months": 12},
    })
    assert response.status_code == 429
    assert response.headers["retry-after"] == "17"
    assert "private" not in response.text


def test_advisor_has_explicit_output_budget_and_does_not_stack_sdk_retries(monkeypatch):
    seen = {}
    monkeypatch.setattr(nodes, "ChatGroq", lambda **kwargs: seen.update(kwargs))
    nodes._new_groq()
    assert seen["max_tokens"] == settings.llm_advisor_max_output_tokens
    assert seen["max_retries"] == 0


def test_history_is_bounded_but_current_request_and_context_remain(monkeypatch):
    monkeypatch.setattr(settings, "llm_advisor_history_messages", 2)
    request = AdvisorRequest.model_validate({
        "conversation_id": "11111111-1111-1111-1111-111111111111",
        "customer_id": "22222222-2222-2222-2222-222222222222",
        "message": "CURRENT", "mode": "LIVE", "context": {"tenure_months": 24},
        "history": [{"role": "customer", "content": text, "ts": "2026-10-18T12:00:00Z"}
                    for text in ["OLD", "RECENT1", "RECENT2"]],
    })
    contents = [m.content for m in nodes._build_messages({"request": request})]
    assert "OLD" not in contents
    assert "RECENT1" in contents and "RECENT2" in contents
    assert contents[-1] == "CURRENT"
    assert any("24" in c for c in contents)


@pytest.mark.asyncio
async def test_same_model_generations_are_serialized_and_cancellation_releases_capacity():
    entered = asyncio.Event()
    release = asyncio.Event()
    order = []
    async def first(_messages):
        order.append('first')
        entered.set()
        await release.wait()
    one = AsyncMock()
    one.ainvoke.side_effect = first
    two = AsyncMock()
    two.ainvoke.return_value = 'second answer'
    task = asyncio.create_task(provider_retry.invoke_with_retry(one, [], model='shared'))
    await entered.wait()
    waiting = asyncio.create_task(provider_retry.invoke_with_retry(two, [], model='shared'))
    await asyncio.sleep(0)
    two.ainvoke.assert_not_awaited()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert await asyncio.wait_for(waiting, 1) == 'second answer'
