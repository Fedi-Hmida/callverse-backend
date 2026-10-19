"""Invalid model function calls must produce a usable, safe advisor response."""
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest
from groq import BadRequestError

from app.advisor import nodes
from app.advisor.graph import run_advisor
from .test_decide import _make_request


@pytest.mark.asyncio
async def test_missing_tool_call_gets_one_structured_repair_without_reexecuting_tools(monkeypatch):
    first = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(tool_calls=[])))
    repair = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(tool_calls=[{
        'name': 'finalize_response', 'args': {'action': 'respond', 'reply': 'Bonjour, je suis votre assistant IA.', 'intent': 'OTHER', 'confidence': .95}
    }])))
    monkeypatch.setattr(nodes, '_get_decide_llm', lambda final=False: repair if final else first)
    response = await run_advisor(_make_request())
    assert response.action.type == 'NONE'
    assert response.reply == 'Bonjour, je suis votre assistant IA.'
    first.ainvoke.assert_awaited_once()
    repair.ainvoke.assert_awaited_once()


@pytest.mark.asyncio
async def test_provider_output_parse_failure_recovers_with_a_validated_json_conclusion(monkeypatch):
    invalid = BadRequestError('output_parse_failed', response=httpx.Response(
        400, request=httpx.Request('POST', 'https://provider.test')), body={})
    llm = SimpleNamespace(ainvoke=AsyncMock(side_effect=invalid))
    conclusion = AsyncMock(return_value={'action': 'respond', 'reply': 'Bonjour, je suis votre assistant IA.', 'intent': 'OTHER', 'confidence': .95})
    monkeypatch.setattr(nodes, '_get_decide_llm', lambda **_: llm)
    monkeypatch.setattr(nodes, '_json_conclusion', conclusion)
    response = await run_advisor(_make_request())
    assert response.action.type == 'NONE'
    assert response.tool_calls == []
    assert response.reply == 'Bonjour, je suis votre assistant IA.'
    assert llm.ainvoke.await_count == 2
    conclusion.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("changes", [
    {"reply": ""}, {"reply": "   "}, {"confidence": "not a number"},
    {"confidence": float("nan")}, {"confidence": 1.5},
    {"action": "BLOCK_CARD"}, {"intent": "UNKNOWN"}, {"reply": None},
])
async def test_invalid_final_response_escalates_instead_of_raising(monkeypatch, changes):
    arguments = {"action": "respond", "reply": "Je vous aide.", "intent": "CARD", "confidence": .9}
    arguments.update(changes)
    llm = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(
        tool_calls=[{"name": "finalize_response", "args": arguments}]
    )))
    monkeypatch.setattr(nodes, "_get_decide_llm", lambda **_: llm)

    response = await run_advisor(_make_request())

    assert response.action.type == "ESCALATE"
    assert response.confidence == 0
    assert response.reply.strip()
    assert llm.ainvoke.await_count == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("call", [
    {"name": "unsupported_tool", "args": {}},
    {"name": "get_customer", "args": "not an object"},
    {"name": "finalize_response", "args": None},
    {},
])
async def test_unusable_function_call_escalates_without_executing_a_tool(monkeypatch, call):
    llm = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(tool_calls=[call])))
    implementation = AsyncMock()
    monkeypatch.setattr(nodes, "_get_decide_llm", lambda **_: llm)
    monkeypatch.setattr(nodes, "TOOL_IMPLEMENTATIONS", {"get_customer": implementation})
    monkeypatch.setattr(nodes, "_json_conclusion", AsyncMock(return_value=None))

    response = await run_advisor(_make_request())

    assert response.action.type == "ESCALATE"
    assert response.tool_calls == []
    implementation.assert_not_awaited()


@pytest.mark.asyncio
async def test_provider_schema_repair_preserves_tool_result_without_repeating_tool(monkeypatch):
    invalid = BadRequestError('tool_use_failed', response=httpx.Response(
        400, request=httpx.Request('POST', 'https://provider.test')), body={'error': {'code': 'tool_use_failed'}})
    llm = SimpleNamespace(ainvoke=AsyncMock(side_effect=[
        SimpleNamespace(tool_calls=[{'name': 'get_customer', 'args': {}}]),
        invalid,
        SimpleNamespace(tool_calls=[{'name': 'finalize_response', 'args': {
            'action': 'respond', 'reply': 'Vous avez deux comptes.', 'intent': 'OTHER', 'confidence': .95}}]),
    ]))
    implementation = AsyncMock(return_value={'accounts': [{'type': 'CURRENT'}, {'type': 'SAVINGS'}]})
    monkeypatch.setattr(nodes, '_get_decide_llm', lambda **_: llm)
    monkeypatch.setattr(nodes, 'TOOL_IMPLEMENTATIONS', {'get_customer': implementation})

    response = await run_advisor(_make_request('Combien de comptes ai-je ?'))

    assert response.action.type == 'NONE'
    assert response.reply == 'Vous avez deux comptes.'
    assert len(response.tool_calls) == 1
    implementation.assert_awaited_once()
    repair_messages = llm.ainvoke.await_args_list[-1].args[0]
    assert 'jamais ACCOUNTS' in repair_messages[-1].content
    assert 'deux' in response.reply


@pytest.mark.asyncio
async def test_repeated_provider_schema_failure_still_escalates_with_bounded_attempts(monkeypatch):
    invalid = BadRequestError('tool_use_failed', response=httpx.Response(
        400, request=httpx.Request('POST', 'https://provider.test')), body={})
    llm = SimpleNamespace(ainvoke=AsyncMock(side_effect=invalid))
    monkeypatch.setattr(nodes, '_get_decide_llm', lambda **_: llm)

    response = await run_advisor(_make_request())

    assert response.action.type == 'ESCALATE'
    assert response.confidence == 0
    assert llm.ainvoke.await_count == 2
