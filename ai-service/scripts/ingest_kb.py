"""
Script d'ingestion de la base de connaissances.

Lit les articles Markdown dans app/advisor/kb/articles/, extrait le
frontmatter (category, title, tags), decoupe le contenu en chunks,
et insere le tout dans kb_article / kb_chunk via app.db.pgvector.

Un article deja present conserve son contenu et est reindexe sans doublons.

Usage : python3 -m scripts.ingest_kb
"""
from __future__ import annotations

import asyncio
import re
from pathlib import Path

from app.db import pgvector

ARTICLES_DIR = Path(__file__).parent.parent / "app" / "advisor" / "kb" / "articles"

from app.advisor.kb.chunking import chunk_text, CHUNK_MAX_CHARS, CHUNK_OVERLAP_CHARS


def parse_frontmatter(text: str) -> tuple[dict, str]:
    """Extrait un frontmatter YAML minimal (category, title, tags) sans dependance externe."""
    match = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.DOTALL)
    if not match:
        raise ValueError("Frontmatter manquant ou mal forme")

    raw_meta, body = match.groups()
    meta: dict = {}
    for line in raw_meta.strip().splitlines():
        key, _, value = line.partition(":")
        key = key.strip()
        value = value.strip()
        if key == "tags":
            value = value.strip("[]")
            meta[key] = [t.strip() for t in value.split(",") if t.strip()]
        else:
            meta[key] = value

    return meta, body.strip()


async def ingest_file(path: Path) -> None:
    raw = path.read_text(encoding="utf-8")
    meta, body = parse_frontmatter(raw)
    title = meta.get("title", path.stem)

    existing_id = await pgvector.find_article_by_title(title)
    if existing_id:
        pool = await pgvector.get_pool()
        async with pool.acquire() as conn:
            existing_content = await conn.fetchval(
                "SELECT content FROM kb_article WHERE id=$1", existing_id)
        count = await pgvector.index_article(existing_id, chunk_text(existing_content))
        print(f"  {path.name} -> deja present, index repare ({count} chunks)")
        return

    article_id = await pgvector.insert_article(
        category=meta.get("category", "GENERAL"),
        title=title,
        content=body,
        tags=meta.get("tags", []),
    )

    chunks = chunk_text(body)
    await pgvector.index_article(article_id, chunks)

    print(f"  {path.name} -> article {article_id} ({len(chunks)} chunks)")


async def main() -> None:
    files = sorted(ARTICLES_DIR.glob("*.md"))
    if not files:
        print(f"Aucun fichier .md trouve dans {ARTICLES_DIR}")
        return

    print(f"Ingestion de {len(files)} articles depuis {ARTICLES_DIR}")
    for path in files:
        await ingest_file(path)

    await pgvector.close_pool()
    print("Ingestion terminee.")


if __name__ == "__main__":
    asyncio.run(main())
