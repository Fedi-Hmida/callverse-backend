"""
Recherche sémantique dans la base de connaissances (RAG).

Passe par app/db/pgvector.py plutôt que par le backend : la KB est indexée
localement au service AI (20-40 articles, cf. cahier des charges).
"""
from __future__ import annotations

from app.db import pgvector


async def search_knowledge_base(query: str, top_k: int = 3) -> list[dict]:
    results = await pgvector.similarity_search(query, top_k=top_k)
    return [
        {
            "article_id": r["id"],
            "title": r["title"],
            "excerpt": r["excerpt"],
            "score": r["score"],
        }
        for r in results
    ]
