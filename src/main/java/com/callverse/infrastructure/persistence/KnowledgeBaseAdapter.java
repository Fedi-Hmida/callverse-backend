package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.KnowledgeBase;
import com.callverse.infrastructure.persistence.repositories.KbArticleRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link KnowledgeBase} with a LIKE query over {@code kb_article}.
 *
 * <p><strong>The escaping is the security-relevant line.</strong> The search text comes from an
 * agent, which got it from a customer. Passed to LIKE unescaped, {@code %} would match every
 * article and {@code _} any character, so a query could enumerate the knowledge base instead of
 * searching it. The escape character is escaped first, then the two wildcards.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class KnowledgeBaseAdapter implements KnowledgeBase {

    private static final char ESCAPE = '!';

    private final KbArticleRepository repository;

    @Override
    public List<Article> searchPublished(String text, int limit) {
        return repository.searchPublished(containsPattern(text), PageRequest.of(0, limit)).stream()
                .map(a -> new Article(
                        a.getId(),
                        a.getCategory(),
                        a.getTitle(),
                        a.getContent(),
                        a.getTags() == null ? List.of() : List.of(a.getTags()),
                        a.getVersion(),
                        a.getUpdatedAt()))
                .toList();
    }

    static String containsPattern(String text) {
        String literal =
                text.toLowerCase(Locale.ROOT)
                        .replace(String.valueOf(ESCAPE), "" + ESCAPE + ESCAPE)
                        .replace("%", ESCAPE + "%")
                        .replace("_", ESCAPE + "_");
        return "%" + literal + "%";
    }
}
