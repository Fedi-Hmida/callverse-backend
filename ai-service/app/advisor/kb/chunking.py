"""Paragraph chunking shared by the serving image and ingestion commands."""

CHUNK_MAX_CHARS = 500
CHUNK_OVERLAP_CHARS = 80


def chunk_text(text: str, max_chars: int = CHUNK_MAX_CHARS, overlap: int = CHUNK_OVERLAP_CHARS) -> list[str]:
    paragraphs = [paragraph.strip() for paragraph in text.split('\n\n') if paragraph.strip()]
    chunks = []
    current = ''
    for paragraph in paragraphs:
        if len(current) + len(paragraph) + 2 <= max_chars:
            current = f'{current}\n\n{paragraph}' if current else paragraph
        else:
            if current:
                chunks.append(current)
            current = paragraph
    if current:
        chunks.append(current)
    if overlap and len(chunks) > 1:
        return [chunks[0]] + [f'{chunks[i - 1][-overlap:]}\n\n{chunks[i]}' for i in range(1, len(chunks))]
    return chunks
