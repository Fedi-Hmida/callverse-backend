"""
Script d'ingestion de la base de connaissances.

Lit les articles Markdown dans app/advisor/kb/articles/, extrait le
frontmatter (category, title, tags), decoupe le contenu en chunks,
et insere le tout dans kb_article / kb_chunk via app.db.pgvector.

Idempotent : un article deja present (meme titre exact) est ignore,
pas reinsere.

Usage : python3 -m scripts.ingest_kb
"""
from __future__ import annotations

import asyncio
import re
from pathlib import Path

from app.db import pgvector

ARTICLES_DIR = Path(__file__).parent.parent / "app" / "advisor" / "kb" / "articles"

CHUNK_MAX_CHARS = 500
CHUNK_OVERLAP_CHARS = 80


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


def chunk_text(text: str, max_chars: int = CHUNK_MAX_CHARS, overlap: int = CHUNK_OVERLAP_CHARS) -> list[str]:
    """Decoupe par paragraphes, en regroupant jusqu'a max_chars avec un leger recouvrement."""
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
    chunks: list[str] = []
    current = ""

    for para in paragraphs:
        if len(current) + len(para) + 2 <= max_chars:
            current = f"{current}\n\n{para}" if current else para
        else:
            if current:
                chunks.append(current)
            current = para

    if current:
        chunks.append(current)

    if overlap and len(chunks) > 1:
        overlapped = [chunks[0]]
        for i in range(1, len(chunks)):
            tail = chunks[i - 1][-overlap:]
            overlapped.append(f"{tail}\n\n{chunks[i]}")
        return overlapped

    return chunks


async def ingest_file(path: Path) -> None:
    raw = path.read_text(encoding="utf-8")
    meta, body = parse_frontmatter(raw)
    title = meta.get("title", path.stem)

    existing_id = await pgvector.find_article_by_title(title)
    if existing_id:
        print(f"  {path.name} -> deja present (article {existing_id}), ignore")
        return

    article_id = await pgvector.insert_article(
        category=meta.get("category", "GENERAL"),
        title=title,
        content=body,
        tags=meta.get("tags", []),
    )

    chunks = chunk_text(body)
    for i, chunk in enumerate(chunks):
        await pgvector.insert_chunk(article_id=article_id, chunk_index=i, content=chunk)

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