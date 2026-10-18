"""Index published procedures without editing their text. Safe to rerun.

Run from ai-service: python -m scripts.index_published_kb
"""
import asyncio
from app.db import pgvector
from scripts.ingest_kb import chunk_text

async def main():
    try:
        pool = await pgvector.get_pool()
        async with pool.acquire() as conn:
            articles = await conn.fetch(
                "SELECT id,content FROM kb_article WHERE published=TRUE ORDER BY id")
        count = 0
        for article in articles:
            count += await pgvector.index_article(str(article['id']), chunk_text(article['content']))
        print(f"Indexed {len(articles)} published articles: {count} chunks.")
    finally:
        await pgvector.close_pool()

if __name__ == '__main__':
    asyncio.run(main())
