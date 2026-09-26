package com.callverse.host.api.dto.response.internal;

import com.callverse.core.application.interfaces.KnowledgeBase.Article;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tool response for {@code GET /internal/kb/search}. <strong>Provisional.</strong> Each article
 * carries its id and version so the agent can cite its sources — the project's hallucination metric
 * depends on the agent reporting which articles it used.
 */
@Schema(description = "Published knowledge-base articles matching the query. Provisional.")
public record KnowledgeSearchResponse(
        @Schema(example = "box") String query, List<ArticleResponse> articles) {

    public record ArticleResponse(
            UUID id,
            @Schema(example = "INTERNET") String category,
            String title,
            String content,
            List<String> tags,
            @Schema(example = "1") int version,
            Instant updatedAt) {}

    public static KnowledgeSearchResponse from(String query, List<Article> articles) {
        return new KnowledgeSearchResponse(
                query,
                articles.stream()
                        .map(a -> new ArticleResponse(
                                a.id(), a.category(), a.title(), a.content(), a.tags(), a.version(), a.updatedAt()))
                        .toList());
    }
}
