package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.KnowledgeBase.Article;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published knowledge-base articles matching a search. Drafts never appear. */
@Schema(name = "KnowledgeArticlesResponse", description = "Published articles matching a search")
public record KnowledgeArticlesResponse(
        @Schema(requiredMode = REQUIRED, example = "opposition carte") String query,
        @Schema(requiredMode = REQUIRED) List<KnowledgeArticle> articles) {

    @Schema(name = "KnowledgeArticle", description = "One published article")
    public record KnowledgeArticle(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED, example = "CARDS") String category,
            @Schema(requiredMode = REQUIRED, example = "Faire opposition a votre carte") String title,
            @Schema(requiredMode = REQUIRED) String content,
            @Schema(requiredMode = REQUIRED) List<String> tags,
            @Schema(requiredMode = REQUIRED, example = "1") int version,
            @Schema(requiredMode = REQUIRED) Instant updatedAt) {}

    public static KnowledgeArticlesResponse of(String query, List<Article> articles) {
        return new KnowledgeArticlesResponse(
                query,
                articles.stream()
                        .map(a -> new KnowledgeArticle(
                                a.id(), a.category(), a.title(), a.content(), a.tags(), a.version(), a.updatedAt()))
                        .toList());
    }
}
