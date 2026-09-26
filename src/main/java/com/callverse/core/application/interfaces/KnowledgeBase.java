package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Search over the knowledge base, for the agent's retrieval tool.
 *
 * <p><strong>Published articles only, and the text is literal.</strong> Unpublished drafts exist and
 * must never reach an agent ({@code OWNERSHIP_RULES.md} E4). The search text is matched as written:
 * characters that are wildcards to SQL, such as {@code %} and {@code _}, match only themselves, so a
 * query of {@code %} cannot turn into "return every article".
 *
 * <p>This is substring search, not semantic search. Embeddings over {@code kb_chunk} are the first
 * item on the project's cut list; when they arrive, they arrive behind this same port.
 */
public interface KnowledgeBase {

    /**
     * @param text matched case-insensitively, as a literal substring of title or content
     * @param limit maximum number of articles
     * @return matching published articles, most recently updated first
     */
    List<Article> searchPublished(String text, int limit);

    record Article(
            UUID id,
            String category,
            String title,
            String content,
            List<String> tags,
            int version,
            Instant updatedAt) {}
}
