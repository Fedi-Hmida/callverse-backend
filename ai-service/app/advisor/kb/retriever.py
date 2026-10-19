"""
Recherche sémantique dans la base de connaissances (RAG).

Passe par app/db/pgvector.py plutôt que par le backend : la KB est indexée
localement au service AI (20-40 articles, cf. cahier des charges).
"""
from __future__ import annotations

from app.db import pgvector
from app.advisor.safety import normalize, credit_application_query

# Les résultats sous ce score de similarité sont écartés (hors-sujet probable).
MIN_SCORE = 0.55


def _credit_application_documents(rows: list[dict]) -> list[dict]:
    return [row for row in rows
            if any(word in normalize(row['title']) for word in ('credit', 'pret'))
            and any(word in normalize(row['title']) for word in ('document', 'demande', 'dossier'))
            and 'renegoci' not in normalize(row['title'])]


async def search_knowledge_base(query: str, top_k: int = 3) -> list[dict]:
    # A full natural-language question made lexical AND search miss the target article,
    # while semantic similarity could return a different account procedure.
    text = normalize(query)
    closing = any(term in text for term in ('clotur', 'fermeture de compte', 'fermer un compte', 'fermer mon compte'))
    credit_application = credit_application_query(query)
    focused_query = 'clôture compte' if closing else 'documents crédit' if credit_application else query
    # A matching current published article is enough for this narrow FAQ. Avoid
    # loading embeddings (and their cold-start delay) when its title matches.
    results = _credit_application_documents(await pgvector.published_text_search(focused_query, top_k=10)) if credit_application else []
    if not results:
        results = await pgvector.similarity_search(focused_query, top_k=10 if closing or credit_application else top_k)
    if closing:
        results = [row for row in results if 'clotur' in normalize(row['title'] + ' ' + row['excerpt'])]
        title_matches = [row for row in results if 'clotur' in normalize(row['title'])]
        if title_matches:
            results = title_matches
    if credit_application:
        results = _credit_application_documents(results)
    results = sorted(results, key=lambda r: r["score"], reverse=True)
    relevant = [r for r in results if r["score"] >= MIN_SCORE]
    results = relevant[:top_k]
    return [
        {
            "article_id": r["id"],
            "title": r["title"],
            "excerpt": r["excerpt"],
            "score": r["score"],
        }
        for r in results
    ]
