from unittest.mock import AsyncMock, patch

import pytest

from app.advisor.graph import run_advisor
from app.advisor.kb import retriever
from app.advisor.safety import credit_application_query, procedure_query
from app.provider_retry import ProviderRateLimited
from .test_advisor_graph import _make_request


@pytest.mark.parametrize('message', [
    'je veux un crédit comment faire', 'Bonjour, comment demander un prêt ?',
    'Comment préparer une demande de crédit à la consommation ?',
])
def test_credit_application_steps_use_published_documentation(message):
    assert credit_application_query(message)
    assert procedure_query(message)


@pytest.mark.parametrize('message', [
    'Accordez-moi un crédit de 20000 euros.', 'Quel taux pour mon crédit ?',
    'Mon prêt est-il accepté ?', 'Comment obtenir un meilleur taux pour mon prêt ?',
    'Comment faire pour rembourser mon crédit ?', 'Comment baisser mes mensualités de crédit ?',
    'Comment obtenir un crédit déjà accordé ?',
])
def test_personal_loan_decisions_are_not_replaced_by_application_documents(message):
    assert not credit_application_query(message)


@pytest.mark.asyncio
async def test_exact_customer_message_is_answered_even_when_model_quota_is_exhausted():
    request = _make_request().model_copy(update={'message': 'je veux un crédit comment faire'})
    documents = [{'article_id': '11111111-1111-1111-1111-111111111111',
                  'title': 'Documents nécessaires pour une demande de crédit',
                  'excerpt': "Pour une demande de crédit à la consommation, préparer les justificatifs de revenus. L'accord final dépend de l'étude du dossier.",
                  'score': .9}]
    with patch('app.advisor.nodes.search_knowledge_base', AsyncMock(return_value=documents)), \
         patch('app.advisor.nodes.invoke_with_retry', AsyncMock(side_effect=ProviderRateLimited(948))) as provider:
        response = await run_advisor(request)
    provider.assert_not_awaited()
    assert response.action.type == 'NONE'
    assert response.intent == 'CREDIT'
    assert 'justificatifs de revenus' in response.reply
    assert 'Quel type de crédit' in response.reply
    assert 'ne constituent pas un accord' in response.reply
    assert len(response.sources) == 1
    assert len(response.tool_calls) == 1
    assert response.tool_calls[0].tool == 'search_knowledge_base'
    assert response.tool_calls[0].ok


@pytest.mark.asyncio
async def test_credit_application_never_invents_missing_documentation():
    request = _make_request().model_copy(update={'message': 'je veux un crédit comment faire'})
    with patch('app.advisor.nodes.search_knowledge_base', AsyncMock(return_value=[])), \
         patch('app.advisor.nodes.invoke_with_retry', AsyncMock()) as provider:
        response = await run_advisor(request)
    provider.assert_not_awaited()
    assert response.action.type == 'ESCALATE'
    assert not response.sources
    assert 'documentation' in response.reply.lower()


@pytest.mark.asyncio
async def test_credit_application_does_not_quote_account_opening_or_renegotiation(monkeypatch):
    monkeypatch.setattr(retriever.pgvector, 'published_text_search', AsyncMock(return_value=[]))
    search = AsyncMock(return_value=[
        {'id': 'account', 'title': 'Ouverture de compte', 'excerpt': 'Pièces pour ouvrir un compte.', 'score': .95},
        {'id': 'renegotiate', 'title': 'Renégociation de crédit', 'excerpt': 'Révision du taux.', 'score': .9},
        {'id': 'credit', 'title': 'Documents nécessaires pour une demande de crédit', 'excerpt': 'Documents du dossier.', 'score': .8},
    ])
    monkeypatch.setattr(retriever.pgvector, 'similarity_search', search)
    results = await retriever.search_knowledge_base('je veux un crédit comment faire')
    assert [row['article_id'] for row in results] == ['credit']
    search.assert_awaited_once_with('documents crédit', top_k=10)


@pytest.mark.asyncio
async def test_matching_published_credit_document_avoids_embedding_cold_start(monkeypatch):
    lexical = AsyncMock(return_value=[{'id': 'credit', 'title': 'Documents pour une demande de crédit',
                                      'excerpt': 'Préparer les justificatifs de revenus.', 'score': .6}])
    semantic = AsyncMock(side_effect=AssertionError('No embedding model needed'))
    monkeypatch.setattr(retriever.pgvector, 'published_text_search', lexical)
    monkeypatch.setattr(retriever.pgvector, 'similarity_search', semantic)
    result = await retriever.search_knowledge_base('je veux un crédit comment faire')
    assert [row['article_id'] for row in result] == ['credit']
    lexical.assert_awaited_once_with('documents crédit', top_k=10)
    semantic.assert_not_awaited()
