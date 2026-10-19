from unittest.mock import AsyncMock
import pytest
from app.advisor import nodes
from app.advisor.graph import run_advisor
from app.advisor.safety import interface_or_greeting, local_welcome_reply
from .test_decide import _make_request

@pytest.mark.parametrize('text', ['Bonjour', 'Bonjour, présentez votre rôle en une phrase.', 'Merci. Où retrouver mes conversations dans cette application ?'])
@pytest.mark.asyncio
async def test_social_and_interface_queries_work_without_any_model_or_customer_tools(monkeypatch, text):
    conclusion = AsyncMock(return_value={'action': 'respond', 'reply': 'Je suis votre assistant IA.', 'intent': 'OTHER', 'confidence': .95})
    planner = AsyncMock()
    monkeypatch.setattr(nodes, '_json_conclusion', conclusion)
    monkeypatch.setattr(nodes, '_get_decide_llm', planner)
    result = await run_advisor(_make_request(text))
    assert result.action.type == 'NONE'
    assert result.tool_calls == []
    conclusion.assert_not_awaited()
    planner.assert_not_called()
    assert result.reply.strip()

@pytest.mark.parametrize('text', ['Bonjour, quel est mon solde ?', 'Où retrouver mes conversations et mon code PIN dans cette application ?', 'Bonjour, bloquez ma carte.', 'Je ne reconnais pas ce paiement.'])
def test_banking_requests_cannot_bypass_the_normal_agent(text):
    assert interface_or_greeting(text) is False


def test_navigation_matches_the_current_customer_space():
    reply = local_welcome_reply('Où retrouver mes conversations dans cette application ?')
    assert 'Messagerie' in reply and 'Nouvelle demande' in reply
    assert 'Mes échanges' not in reply
