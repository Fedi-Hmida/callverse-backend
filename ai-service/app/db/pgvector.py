"""
Connexion + requetes vectorielles pgvector (dim 384, index HNSW).

Le modele d'embedding tourne en local (sentence-transformers), aucune
dependance a un LLM externe pour la recherche KB elle-meme.
"""
from __future__ import annotations

import asyncio
import asyncpg
from sentence_transformers import SentenceTransformer

from app.config import settings

_pool: asyncpg.Pool | None = None
_model: SentenceTransformer | None = None


def _get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        _model = SentenceTransformer("paraphrase-multilingual-MiniLM-L12-v2")
    return _model


async def get_pool() -> asyncpg.Pool:
    global _pool
    if _pool is None:
        _pool = await asyncpg.create_pool(settings.pgvector_dsn, min_size=1, max_size=5)
    return _pool


async def close_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


def _sync_embed_text(text: str) -> list[float]:
    """Encode un texte en vecteur 384-d. Synchrone et local (pas d'appel reseau)."""
    model = _get_model()
    return model.encode(text).tolist()


async def embed_text(text: str) -> list[float]:
    """Encode un texte sans bloquer l'event loop asyncio."""
    return await asyncio.to_thread(_sync_embed_text, text)


async def similarity_search(query: str, top_k: int = 3) -> list[dict]:
    """
    Recherche par similarite cosinus dans kb_chunk, jointe a kb_article
    pour recuperer le titre. Utilise l'index HNSW deja cree sur Neon.
    """
    query_embedding = await embed_text(query)
    embedding_str = "[" + ",".join(str(x) for x in query_embedding) + "]"

    pool = await get_pool()
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT
                a.id AS article_id,
                a.title AS title,
                c.content AS excerpt,
                1 - (c.embedding <=> $1::vector) AS score
            FROM kb_chunk c
            JOIN kb_article a ON a.id = c.article_id
            WHERE a.published = TRUE
            ORDER BY c.embedding <=> $1::vector
            LIMIT $2
            """,
            embedding_str,
            top_k,
        )

    return [
        {
            "id": str(row["article_id"]),
            "title": row["title"],
            "excerpt": row["excerpt"],
            "score": float(row["score"]),
        }
        for row in rows
    ]


async def insert_article(category: str, title: str, content: str, tags: list[str] | None = None) -> str:
    """Insere un article KB et retourne son id. Le chunking se fait a part (voir scripts/ingest_kb.py)."""
    pool = await get_pool()
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            INSERT INTO kb_article (category, title, content, tags, published)
            VALUES ($1, $2, $3, $4, TRUE)
            RETURNING id
            """,
            category,
            title,
            content,
            tags or [],
        )
    return str(row["id"])


async def find_article_by_title(title: str) -> str | None:
    """Retourne l'id de l'article existant portant ce titre exact, sinon None."""
    pool = await get_pool()
    async with pool.acquire() as conn:
        row = await conn.fetchrow("SELECT id FROM kb_article WHERE title = $1", title)
    return str(row["id"]) if row else None


async def insert_chunk(article_id: str, chunk_index: int, content: str) -> None:
    """Insere un chunk avec son embedding calcule localement."""
    embedding = await embed_text(content)
    embedding_str = "[" + ",".join(str(x) for x in embedding) + "]"

    pool = await get_pool()
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO kb_chunk (article_id, chunk_index, content, embedding)
            VALUES ($1, $2, $3, $4::vector)
            """,
            article_id,
            chunk_index,
            content,
            embedding_str,
        )