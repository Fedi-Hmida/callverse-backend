"""
Recherche sémantique dans la base de connaissances (RAG).

Passe par app/db/pgvector.py plutôt que par le backend : la KB est indexée
localement au service AI (20-40 articles, cf. cahier des charges).
"""
from __future__ import annotations

from app.db import pgvector

# Les résultats sous ce score de similarité sont écartés (hors-sujet probable).
MIN_SCORE = 0.55


async def search_knowledge_base(query: str, top_k: int = 3) -> list[dict]:
    results = await pgvector.similarity_search(query, top_k=top_k)
    results = sorted(results, key=lambda r: r["score"], reverse=True)
    relevant = [r for r in results if r["score"] >= MIN_SCORE]
    # garde-fou : on garde toujours le meilleur résultat pour ne pas laisser le LLM sans source
    results = relevant or results[:1]
    return [
        {
            "article_id": r["id"],
            "title": r["title"],
            "excerpt": r["excerpt"],
            "score": r["score"],
        }
        for r in results
    ]
