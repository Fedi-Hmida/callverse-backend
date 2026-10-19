from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from langchain_core.messages import AIMessage, HumanMessage

from app.advisor import nodes
from app.advisor.graph import run_advisor
from app.advisor.safety import local_welcome_reply
from app.config import settings
from app.provider_retry import ProviderRateLimited
from .test_decide import _make_request


@pytest.mark.parametrize('text', ['bonjour', 'Bonsoir !', 'salut', 'merci', "d'accord", 'au revoir'])
@pytest.mark.asyncio
async def test_welcome_does_not_depend_on_provider_quota(monkeypatch, text):
    provider = AsyncMock(side_effect=ProviderRateLimited(999))
    monkeypatch.setattr(nodes, 'invoke_with_retry', provider)
    response = await run_advisor(_make_request(text))
    provider.assert_not_awaited()
    assert response.action.type == 'NONE'
    assert response.reply == local_welcome_reply(text)


@pytest.mark.asyncio
async def test_backup_generation_preserves_tools_and_messages_without_executing_any_tool(monkeypatch):
    monkeypatch.setattr(settings, 'llm_live_advisor_fallback_model', 'backup')
    llm = SimpleNamespace(kwargs={'tools': [{'type': 'function'}], 'tool_choice': 'required'})
    generated = AIMessage(content='Real generated response')
    invoke = AsyncMock(side_effect=[ProviderRateLimited(900), generated])
    bind = Mock(return_value='bound-backup')
    create = Mock(return_value=SimpleNamespace(bind=bind))
    execute = AsyncMock(side_effect=AssertionError('Do not replay a banking tool'))
    monkeypatch.setattr(nodes, 'invoke_with_retry', invoke)
    monkeypatch.setattr(nodes, '_new_groq', create)
    monkeypatch.setattr(nodes, 'execute_tool', execute)
    history = [HumanMessage(content='Current question'), AIMessage(content='Already verified tool results')]
    response = await nodes._invoke_advisor_model(llm, history, model='primary')
    assert response is generated
    bind.assert_called_once_with(**llm.kwargs)
    assert invoke.await_args_list[1].args[1][:len(history)] == history
    assert 'Ne redemande jamais un outil identique' in invoke.await_args_list[1].args[1][-1].content
    assert invoke.await_args_list[1].kwargs['model'] == 'backup'
    execute.assert_not_awaited()


@pytest.mark.parametrize('backup,allow_fallback', [('', True), ('primary', True), ('backup', False)])
@pytest.mark.asyncio
async def test_disabled_identical_or_simulation_backup_does_not_change_models(monkeypatch, backup, allow_fallback):
    monkeypatch.setattr(settings, 'llm_live_advisor_fallback_model', backup)
    invoke = AsyncMock(side_effect=ProviderRateLimited(80))
    monkeypatch.setattr(nodes, 'invoke_with_retry', invoke)
    with pytest.raises(ProviderRateLimited):
        await nodes._invoke_advisor_model(SimpleNamespace(kwargs={}), [], model='primary', allow_fallback=allow_fallback)
    invoke.assert_awaited_once()


@pytest.mark.asyncio
async def test_backup_json_object_is_given_the_validation_schema(monkeypatch):
    monkeypatch.setattr(settings, 'llm_live_advisor_fallback_model', 'qwen/qwen3.8-27b')
    llm = SimpleNamespace(kwargs={'response_format': {'type': 'json_schema', 'json_schema': {'schema': {'required': ['reply']}}}})
    invoke = AsyncMock(side_effect=[ProviderRateLimited(80), AIMessage(content='{"reply":"Test"}')])
    bind = Mock(return_value='bound')
    monkeypatch.setattr(nodes, 'invoke_with_retry', invoke)
    monkeypatch.setattr(nodes, '_new_groq', Mock(return_value=SimpleNamespace(bind=bind)))
    await nodes._invoke_advisor_model(llm, [], model='primary')
    bind.assert_called_once_with(response_format={'type': 'json_object'})
    assert 'required' in invoke.await_args_list[1].args[1][-1].content
    assert llm.kwargs['response_format']['type'] == 'json_schema'


@pytest.mark.asyncio
async def test_backup_quota_failure_is_propagated_without_an_infinite_model_loop(monkeypatch):
    monkeypatch.setattr(settings, 'llm_live_advisor_fallback_model', 'backup')
    invoke = AsyncMock(side_effect=ProviderRateLimited(90))
    monkeypatch.setattr(nodes, 'invoke_with_retry', invoke)
    monkeypatch.setattr(nodes, '_new_groq', Mock(return_value=SimpleNamespace(bind=Mock())))
    with pytest.raises(ProviderRateLimited):
        await nodes._invoke_advisor_model(SimpleNamespace(kwargs={}), [], model='primary')
    assert invoke.await_count == 2


def test_qwen_backup_avoids_reasoning_tokens_and_limits_output(monkeypatch):
    monkeypatch.setattr(settings, 'llm_live_advisor_fallback_model', 'qwen/qwen3.8-27b')
    seen = {}
    monkeypatch.setattr(nodes, 'ChatGroq', lambda **kwargs: seen.update(kwargs))
    nodes._new_groq(model='qwen/qwen3.8-27b')
    assert seen['reasoning_effort'] == 'none'
    assert seen['max_tokens'] <= 1024
