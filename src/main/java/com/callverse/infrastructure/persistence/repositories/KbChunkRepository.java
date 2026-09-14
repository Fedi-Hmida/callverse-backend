package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.KbChunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * RAG retrieval reads chunks directly and never through their article, which is why this is a
 * root despite kb_chunk being a derived table.
 */
@Repository
public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {

    List<KbChunk> findByArticleIdOrderByChunkIndexAsc(UUID articleId);

    /**
     * Used by the re-chunking job to clear an article's chunks before regenerating them. Vector
     * similarity search is deliberately absent: pgvector has no JPA mapping and cosine retrieval
     * over the HNSW index will be issued as native SQL.
     */
    void deleteByArticleId(UUID articleId);
}
