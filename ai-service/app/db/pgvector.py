"""
Connexion + requetes vectorielles pgvector (dim 384, index HNSW).

Le modele d'embedding tourne en local (sentence-transformers), aucune
dependance a un LLM externe pour la recherche KB elle-meme.
"""
from __future__ import annotations

import asyncio
import asyncpg
import logging
import re
from typing import TYPE_CHECKING
if TYPE_CHECKING:
    from sentence_transformers import SentenceTransformer

from app.config import settings

_pool: asyncpg.Pool | None = None
_model: SentenceTransformer | None = None
logger = logging.getLogger(__name__)


def _get_model() -> SentenceTransformer:
    from sentence_transformers import SentenceTransformer
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
    top_k = max(1, min(10, int(top_k)))
    query = query.strip()[:1000]
    if not query:
        return []
    # New/edited published articles are immediately usable, even while their derived
    # semantic index is absent. Drafts never participate in either retrieval path.
    lexical = await published_text_search(query, top_k)
    try:
        query_embedding = await embed_text(query)
    except Exception:
        logger.warning("Knowledge embeddings unavailable; using current published text search")
        return lexical
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
              AND position(c.content in a.content) > 0
            ORDER BY c.embedding <=> $1::vector
            LIMIT $2
            """,
            embedding_str,
            top_k,
        )

    semantic = [
        {
            "id": str(row["article_id"]),
            "title": row["title"],
            "excerpt": row["excerpt"],
            "score": float(row["score"]),
        }
        for row in rows
    ]
    best = {}
    for row in lexical + semantic:
        if row["id"] not in best or row["score"] > best[row["id"]]["score"]:
            best[row["id"]] = row
    return sorted(best.values(), key=lambda row: row["score"], reverse=True)[:top_k]


async def published_text_search(query: str, top_k: int = 3) -> list[dict]:
    pool = await get_pool()
    async with pool.acquire() as conn:
        rows = await conn.fetch("""
            SELECT id AS article_id,title,left(content,1500) excerpt,
                   least(0.99,0.55+ts_rank_cd(to_tsvector('french',title || ' ' || content),
                                            websearch_to_tsquery('french',$1))) score
            FROM kb_article
            WHERE published=TRUE AND to_tsvector('french',title || ' ' || content)
                                     @@ websearch_to_tsquery('french',$1)
            ORDER BY score DESC,id LIMIT $2
            """,query,top_k)
    return [{"id": str(row["article_id"]), "title": row["title"],
             "excerpt": row["excerpt"], "score": float(row["score"])} for row in rows]


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


async def index_article(article_id: str, chunks: list[str]) -> int:
    """Rebuild one article atomically, after all embeddings succeeded.

    A row lock serializes competing index jobs. Repeated jobs replace the derived
    chunks rather than duplicating them; an embedding failure keeps the old index.
    """
    embeddings = [await embed_text(content) for content in chunks]
    pool = await get_pool()
    async with pool.acquire() as conn:
        async with conn.transaction():
            article = await conn.fetchrow(
                "SELECT id,content,published FROM kb_article WHERE id=$1 FOR UPDATE", article_id)
            if article is None:
                raise ValueError("Unknown knowledge article.")
            # Embeddings are calculated before the lock. An editor may have replaced the
            # text meanwhile; never commit an old job over the new article's index.
            normalized = re.sub(r"\s+", " ", article["content"]).strip()
            if not article["published"] or any(re.sub(r"\s+", " ", chunk).strip() not in normalized for chunk in chunks):
                raise ValueError("Knowledge article changed or is no longer published; re-read it before indexing.")
            await conn.execute("DELETE FROM kb_chunk WHERE article_id=$1", article_id)
            await conn.executemany(
                "INSERT INTO kb_chunk (article_id,chunk_index,content,embedding) "
                "VALUES ($1,$2,$3,$4::vector)",
                [(article_id, i, content, '['+','.join(map(str, embedding))+']')
                 for i, (content, embedding) in enumerate(zip(chunks, embeddings))])
    return len(chunks)
