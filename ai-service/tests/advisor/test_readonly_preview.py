from unittest.mock import AsyncMock, patch
import pytest
from app.advisor.nodes import execute_tool, decide
from app.contracts.advisor import Mode
from .test_advisor_graph import _make_request


@pytest.mark.asyncio
@pytest.mark.parametrize('tool', ['get_customer', 'get_transactions', 'check_system_status'])
async def test_preview_can_consult_without_mutation(tool):
    implementation = AsyncMock(return_value={'fixture': 'invented test data'})
    state = {'request': _make_request().model_copy(update={'mode': Mode.preview}),
             'decision': {'action': 'call_tool', 'tool_name': tool, 'arguments': {}}, 'iterations': 1}
    with patch('app.advisor.nodes.TOOL_IMPLEMENTATIONS', {tool: implementation}):
        result = await execute_tool(state)
    implementation.assert_awaited_once()
    assert result['tool_calls'][0]['error'] is None


@pytest.mark.asyncio
@pytest.mark.parametrize('tool', ['create_case', 'apply_credit'])
async def test_preview_never_executes_an_operation_and_stops_retrying(tool):
    implementation = AsyncMock(side_effect=AssertionError('Must never execute'))
    state = {'request': _make_request().model_copy(update={'mode': Mode.preview}),
             'decision': {'action': 'call_tool', 'tool_name': tool, 'arguments': {}}, 'iterations': 1}
    with patch('app.advisor.nodes.TOOL_IMPLEMENTATIONS', {tool: implementation}):
        result = await execute_tool(state)
    implementation.assert_not_awaited()
    state.update(result)
    with patch('app.advisor.nodes._get_decide_llm', side_effect=AssertionError('No retry')):
        conclusion = await decide(state)
    assert conclusion['decision']['action'] == 'escalate'
    assert 'lecture seule' in conclusion['decision']['reason']


@pytest.mark.asyncio
async def test_simulation_disabled_tool_is_not_retried():
    state = {'request': _make_request().model_copy(update={'mode': Mode.simulation}),
             'decision': {'action': 'call_tool', 'tool_name': 'get_customer', 'arguments': {}}, 'iterations': 1}
    state.update(await execute_tool(state))
    with patch('app.advisor.nodes._get_simulation_llm', side_effect=AssertionError('No retry')):
        conclusion = await decide(state)
    assert conclusion['decision']['action'] == 'escalate'
    assert len(state['tool_calls']) == 1
