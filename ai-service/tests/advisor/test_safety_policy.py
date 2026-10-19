from unittest.mock import AsyncMock, patch
import pytest
from app.advisor.graph import run_advisor
from app.advisor.nodes import finalize
from app.advisor.safety import reports_fraud, customer_reply, procedure_query, requests_human
from .test_advisor_graph import _make_request


@pytest.mark.parametrize('message', ['Je veux un conseiller humain', 'Je voudrais parler à une conseillère.',
    'Un conseiller svp', 'I want a human advisor', 'Je veux un conseiller. Je ne veux pas un crédit.'])
def test_explicit_human_requests(message):
    assert requests_human(message)


@pytest.mark.parametrize('message', ['Je ne veux pas de conseiller', 'Pas besoin de conseiller humain',
    'Que fait un conseiller ?', 'Je veux un crédit', 'Bonjour'])
def test_no_invented_human_request(message):
    assert not requests_human(message)


@pytest.mark.asyncio
async def test_human_request_bypasses_unavailable_provider():
    request = _make_request().model_copy(update={'message': 'Je veux un conseiller humain svp'})
    with patch('app.advisor.nodes.invoke_with_retry', AsyncMock(side_effect=AssertionError('No provider call'))) as provider:
        result = await run_advisor(request)
    provider.assert_not_awaited()
    assert result.action.type == 'ESCALATE'
    assert 'Je reste disponible' in result.reply
    assert not result.tool_calls


@pytest.mark.parametrize('message', [
    'Je ne reconnais pas ce paiement.', 'Je n’ai pas autorisé cette transaction.',
    'Ma carte a été volée.', "I don't recognize this charge.", 'Je soupçonne une fraude.',
])
def test_fraud_reports(message):
    assert reports_fraud(message)


@pytest.mark.parametrize('message', [
    'Quelle est la procédure générale en cas de fraude ?', 'Je ne soupçonne pas de fraude.',
    'Ma carte est refusée.', 'Quel est mon solde ?',
])
def test_not_personal_reports(message):
    assert not reports_fraud(message)


@pytest.mark.asyncio
async def test_fraud_does_not_need_an_available_provider():
    request = _make_request().model_copy(update={'message': 'Je ne reconnais pas ce paiement.'})
    with patch('app.advisor.nodes.invoke_with_retry', AsyncMock(side_effect=AssertionError('No provider call'))) as provider:
        result = await run_advisor(request)
    provider.assert_not_awaited()
    assert result.action.type == 'ESCALATE'
    assert result.intent == 'FRAUD'
    assert not result.tool_calls
    assert "Aucun blocage" in result.reply


@pytest.mark.asyncio
async def test_model_fraud_classification_cannot_suppress_handover():
    result = await finalize({'request': _make_request(), 'decision': {
        'action': 'respond', 'reply': 'Tout va bien.', 'intent': 'FRAUD', 'confidence': .99}})
    assert result['action_type'] == 'ESCALATE'
    assert result['reply'] != 'Tout va bien.'


@pytest.mark.asyncio
async def test_procedure_is_extracted_without_inventing_app_actions():
    request = _make_request().model_copy(update={'message': 'Quelle est la procédure pour faire opposition à ma carte ?'})
    excerpt = 'Bloquer la carte immédiatement avec le motif adapté, puis ouvrir un ticket FRAUD.'
    documents = [{'article_id': '11111111-1111-1111-1111-111111111111',
                  'title': 'Opposition carte', 'excerpt': excerpt, 'score': .8}]
    with patch('app.advisor.nodes.search_knowledge_base', AsyncMock(return_value=documents)), \
         patch('app.advisor.nodes.invoke_with_retry', AsyncMock(side_effect=AssertionError('No provider call'))):
        result = await run_advisor(request)
    assert excerpt in result.reply
    assert 'email' not in result.reply
    assert 'gratuit' not in result.reply
    assert "aucune opération" in result.reply
    assert result.action.type == 'NONE'
    assert len(result.sources) == 1
    assert result.tool_calls[0].ok


@pytest.mark.asyncio
async def test_missing_documentation_escalates_without_invented_procedure():
    request = _make_request().model_copy(update={'message': 'Quelle est la procédure de contestation ?'})
    with patch('app.advisor.nodes.search_knowledge_base', AsyncMock(return_value=[])):
        result = await run_advisor(request)
    assert result.action.type == 'ESCALATE'
    assert not result.sources


def test_internal_id_redaction_and_personal_question_routing():
    assert '11111111' not in customer_reply('Ticket 11111111-1111-1111-1111-111111111111')
    assert not procedure_query("Comment connaître le nombre de mes comptes ?")


@pytest.mark.parametrize('message', ['Quels documents préparer pour clôturer mon compte ?', 'Comment ouvrir un compte ?', 'Quelles conditions pour un crédit ?'])
def test_documented_banking_procedure_queries_use_retrieval_first(message):
    assert procedure_query(message)


@pytest.mark.asyncio
async def test_card_block_execution_claim_is_replaced_by_an_honest_handover():
    result = await finalize({'request': _make_request(), 'decision': {
        'action': 'respond', 'reply': "J'ai bloqué votre carte.", 'intent': 'CARD', 'confidence': .99}})
    assert result['action_type'] == 'ESCALATE'
    assert 'ne peux pas confirmer' in result['reply']
