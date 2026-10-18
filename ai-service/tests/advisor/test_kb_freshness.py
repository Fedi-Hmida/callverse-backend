from contextlib import asynccontextmanager
from unittest.mock import AsyncMock

import pytest

from app.db import pgvector


@pytest.mark.asyncio
async def test_a_new_published_article_is_usable_when_embedding_model_is_unavailable(monkeypatch):
    current = [{"id": "article", "title": "Procédure modifiée", "excerpt": "La nouvelle procédure", "score": .7}]
    monkeypatch.setattr(pgvector, "published_text_search", AsyncMock(return_value=current))
    monkeypatch.setattr(pgvector, "embed_text", AsyncMock(side_effect=RuntimeError("No local embedding model")))
    assert await pgvector.similarity_search("procédure", 3) == current


@pytest.mark.asyncio
async def test_old_index_job_cannot_replace_chunks_after_an_editor_changes_the_article(monkeypatch):
    class Connection:
        def __init__(self):
            self.execute = AsyncMock()
            self.executemany = AsyncMock()
            self.fetchrow = AsyncMock(return_value={"id": "article", "content": "New approved procedure", "published": True})
        @asynccontextmanager
        async def transaction(self):
            yield
    connection = Connection()
    class Pool:
        @asynccontextmanager
        async def acquire(self):
            yield connection
    monkeypatch.setattr(pgvector, "get_pool", AsyncMock(return_value=Pool()))
    monkeypatch.setattr(pgvector, "embed_text", AsyncMock(return_value=[0.0] * 384))
    with pytest.raises(ValueError, match="changed"):
        await pgvector.index_article("article", ["Old withdrawn instructions"])
    connection.execute.assert_not_awaited()
    connection.executemany.assert_not_awaited()
