package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.KbArticle;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Back-office write model of the RAG pipeline. Retrieval never reads this table; it reads the
 * chunks derived from it.
 */
@Repository
public interface KbArticleRepository extends JpaRepository<KbArticle, UUID> {

    /** Unpublished drafts exist but must never reach retrieval. */
    List<KbArticle> findByPublishedTrue();

    List<KbArticle> findByCategoryAndPublishedTrue(String category);
}
