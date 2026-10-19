from unittest.mock import AsyncMock
import pytest
from app.advisor.kb import retriever

@pytest.mark.asyncio
async def test_closure_question_does_not_quote_a_higher_scoring_account_opening_article(monkeypatch):
    search = AsyncMock(return_value=[
        {'id': 'opening', 'title': 'Compte professionnel', 'excerpt': 'Documents pour ouvrir un compte professionnel.', 'score': .9},
        {'id': 'closing', 'title': 'Conditions de clôture de compte', 'excerpt': 'Avant la clôture, vérifier les prélèvements.', 'score': .8},
    ])
    monkeypatch.setattr(retriever.pgvector, 'similarity_search', search)
    result = await retriever.search_knowledge_base('Quels documents préparer pour clôturer mon compte ?')
    assert [row['article_id'] for row in result] == ['closing']
    search.assert_awaited_once_with('clôture compte', top_k=10)

@pytest.mark.asyncio
async def test_no_closure_source_is_better_than_an_unrelated_published_source(monkeypatch):
    monkeypatch.setattr(retriever.pgvector, 'similarity_search', AsyncMock(return_value=[
        {'id': 'opening', 'title': 'Ouverture de compte', 'excerpt': 'Documents nécessaires.', 'score': .95}
    ]))
    assert await retriever.search_knowledge_base('Comment clôturer un compte ?') == []
