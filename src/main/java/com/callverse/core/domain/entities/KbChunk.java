package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A retrievable fragment of a {@link KbArticle}. Maps {@code kb_chunk}.
 *
 * <p><strong>{@code embedding vector(384)} is deliberately not mapped here.</strong> Hibernate 6 has
 * no type for pgvector, and the schema reference instructs that it stay unmapped in this phase.
 * Omitting the field rather than marking it {@code insertable=false, updatable=false} is the
 * stronger choice: a field that exists but silently ignores every write is a trap for the next
 * developer, whereas an absent field forces them to find the native query that is coming.
 *
 * <p>This is safe under {@code ddl-auto: validate} because validation is directional — Hibernate
 * checks that every mapped attribute exists in the database, not that every column is mapped. The
 * column and its HNSW index exist and are simply invisible to JPA.
 *
 * <p>Writes to {@code embedding}, and cosine-similarity retrieval over the HNSW index, will be done
 * through native SQL when the RAG pipeline lands.
 */
@Entity
@Table(name = "kb_chunk")
@Getter
@Setter
@NoArgsConstructor
public class KbChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private KbArticle article;

    /** Position within the article, zero-based. Restores reading order after retrieval. */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    // embedding vector(384) — intentionally absent. See the class javadoc.
}
