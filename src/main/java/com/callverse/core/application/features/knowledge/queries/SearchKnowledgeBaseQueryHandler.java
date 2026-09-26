package com.callverse.core.application.features.knowledge.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.KnowledgeBase;
import com.callverse.core.application.interfaces.KnowledgeBase.Article;
import java.util.List;
import java.util.Objects;

/**
 * Answers the agent tool {@code GET /internal/kb/search?q=&k=} ({@code OWNERSHIP_RULES.md} E4).
 *
 * <p><strong>Bounds.</strong> The text is trimmed and must be 2 to 100 characters: one character
 * matches almost every article, and a whole paragraph is a sign the agent is pasting the
 * conversation instead of querying. {@code k} defaults to 5, the contract's example, and is capped
 * at 10: every article returned lands in the model's context window, so more is not better.
 */
public class SearchKnowledgeBaseQueryHandler {

    static final int MIN_TEXT = 2;
    static final int MAX_TEXT = 100;
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 10;

    private final KnowledgeBase knowledgeBase;

    public SearchKnowledgeBaseQueryHandler(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = Objects.requireNonNull(knowledgeBase, "knowledgeBase must not be null");
    }

    public List<Article> handle(SearchKnowledgeBaseQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String text = query.text() == null ? "" : query.text().trim();
        if (text.length() < MIN_TEXT || text.length() > MAX_TEXT) {
            throw new InvalidRequestException("q must be %d to %d characters".formatted(MIN_TEXT, MAX_TEXT));
        }
        int limit = query.limit() == null ? DEFAULT_LIMIT : query.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidRequestException("k must be between 1 and %d".formatted(MAX_LIMIT));
        }
        return knowledgeBase.searchPublished(text, limit);
    }
}
