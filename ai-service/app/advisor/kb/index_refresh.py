"""Maintain derived embeddings for current published procedures in the background."""
import asyncio
import logging

from app.db import pgvector
from app.advisor.kb.chunking import chunk_text

logger = logging.getLogger(__name__)


async def refresh_published_index() -> int:
    pool = await pgvector.get_pool()
    async with pool.acquire() as conn:
        articles = await conn.fetch("""
            SELECT a.id,a.content,
                   coalesce(array_agg(c.content ORDER BY c.chunk_index)
                            FILTER (WHERE c.id IS NOT NULL), ARRAY[]::text[]) AS indexed_chunks
            FROM kb_article a LEFT JOIN kb_chunk c ON c.article_id=a.id
            WHERE a.published=TRUE GROUP BY a.id ORDER BY a.id
            """)
    updated = 0
    for article in articles:
        chunks = chunk_text(article['content'])
        if chunks == list(article['indexed_chunks']):
            continue
        # index_article rechecks the current text/publication status under lock.
        try:
            await pgvector.index_article(str(article['id']), chunks)
            updated += 1
        except ValueError:
            logger.info("Knowledge article changed during indexing; retrying on the next refresh")
    return updated


async def maintain_published_index(interval: float) -> None:
    while True:
        try:
            count = await refresh_published_index()
            if count:
                logger.info("Refreshed published knowledge articles: %s", count)
        except Exception:
            # Current published text remains searchable while embeddings are unavailable.
            logger.warning("Knowledge index refresh unavailable; retrying later")
        await asyncio.sleep(interval)
