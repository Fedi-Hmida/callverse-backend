from contextlib import asynccontextmanager
from unittest.mock import AsyncMock
import pytest
from app.advisor.kb import index_refresh


@pytest.mark.asyncio
async def test_only_missing_or_changed_published_articles_are_reindexed(monkeypatch):
    fetch = AsyncMock(return_value=[
        {'id': 'unchanged', 'content': 'Current procedure', 'indexed_chunks': ['Current procedure']},
        {'id': 'new', 'content': 'New procedure', 'indexed_chunks': []},
        {'id': 'edited', 'content': 'Updated procedure', 'indexed_chunks': ['Withdrawn instructions']},
    ])
    class Connection:
        async def fetch(self, sql):
            assert 'a.published=TRUE' in sql
            return await fetch()
    class Pool:
        @asynccontextmanager
        async def acquire(self):
            yield Connection()
    monkeypatch.setattr(index_refresh.pgvector, 'get_pool', AsyncMock(return_value=Pool()))
    index = AsyncMock()
    monkeypatch.setattr(index_refresh.pgvector, 'index_article', index)
    assert await index_refresh.refresh_published_index() == 2
    assert [call.args for call in index.await_args_list] == [
        ('new', ['New procedure']), ('edited', ['Updated procedure'])]
