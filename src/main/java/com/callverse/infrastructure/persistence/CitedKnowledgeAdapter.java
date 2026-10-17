package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.CitedKnowledge;
import com.callverse.core.application.interfaces.QualityAgent.SourceDocument;
import com.callverse.core.domain.entities.KbArticle;
import com.callverse.core.domain.entities.Message;
import com.callverse.infrastructure.persistence.repositories.KbArticleRepository;
import com.callverse.infrastructure.persistence.repositories.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the AI turns' private {@code sources} trace ({@code [{"kb_article_id": ..., "score": ...}]},
 * written by {@code postAgentTurn}) and loads the cited articles.
 *
 * <p>Tolerant by design: a malformed entry, an id that is not a UUID or an article deleted since is
 * skipped, never an error, because a missing citation only lowers the grade's grounding while an
 * exception would cost the grade itself.
 *
 * <p>Bounded for the judge's context window: the first {@value #MAX_DOCUMENTS} existing articles in
 * citation order, each cut to {@value #MAX_CONTENT} characters. The current version is sent; an
 * article edited after the AI used it is graded against its new text (no history is kept).
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CitedKnowledgeAdapter implements CitedKnowledge {

    static final int MAX_DOCUMENTS = 10;
    static final int MAX_CONTENT = 1500;
    static final String CHUNK_ID = "article";

    private final MessageRepository messages;
    private final KbArticleRepository articles;

    @Override
    public List<SourceDocument> citedInAiTurns(UUID conversationId) {
        Set<UUID> cited = new LinkedHashSet<>();
        for (Message turn : messages.findByConversationIdAndAiGeneratedTrueOrderBySentAtAsc(conversationId)) {
            JsonNode sources = turn.getSources();
            if (sources == null || !sources.isArray()) {
                continue;
            }
            for (JsonNode source : sources) {
                articleId(source.path("kb_article_id").asText(null)).ifPresent(cited::add);
            }
        }
        // Existing articles first, then the limit: deleted citations must not crowd out real ones.
        Map<UUID, KbArticle> found = articles.findAllById(cited).stream()
                .collect(Collectors.toMap(KbArticle::getId, Function.identity()));
        return cited.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .limit(MAX_DOCUMENTS)
                .map(article -> new SourceDocument(
                        article.getId().toString(), CHUNK_ID, bounded(article.getTitle() + "\n" + article.getContent())))
                .toList();
    }

    private static String bounded(String content) {
        return content.length() <= MAX_CONTENT ? content : content.substring(0, MAX_CONTENT - 1) + "…";
    }

    private static Optional<UUID> articleId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
