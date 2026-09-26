package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.KbArticle;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Back-office write model of the RAG pipeline, and — until embeddings exist — the table the agent's
 * text search reads directly. Semantic retrieval, when it lands, reads the chunks derived from it.
 */
@Repository
public interface KbArticleRepository extends JpaRepository<KbArticle, UUID> {

    /** Unpublished drafts exist but must never reach retrieval. */
    List<KbArticle> findByPublishedTrue();

    List<KbArticle> findByCategoryAndPublishedTrue(String category);

    /**
     * Case-insensitive substring search over title and content, published articles only.
     *
     * <p>{@code pattern} must already be a LIKE pattern with {@code !} as the escape character —
     * the caller escapes {@code !}, {@code %} and {@code _} so that user text is matched literally.
     * {@code !} rather than a backslash, to keep the escape identical in Java, JPQL and SQL.
     *
     * <p><strong>No index serves this</strong>: a leading {@code %} rules out a B-tree, and there is
     * no trigram or full-text index on {@code kb_article}. At demo volume a sequential scan of a few
     * hundred articles is fine; a GIN index is the upgrade if it ever is not.
     */
    @Query("""
           select a
             from KbArticle a
            where a.published = true
              and (lower(a.title) like :pattern escape '!'
                   or lower(a.content) like :pattern escape '!')
            order by a.updatedAt desc
           """)
    List<KbArticle> searchPublished(@Param("pattern") String pattern, Pageable pageable);
}
