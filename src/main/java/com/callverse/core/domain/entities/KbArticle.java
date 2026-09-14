package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A knowledge-base article, written by the back-office. Maps {@code kb_article}.
 *
 * <p>The write model of the RAG pipeline: the Angular back-office edits articles here, a job
 * re-chunks and re-embeds them into {@link KbChunk}, and retrieval reads only the chunks. One source
 * of truth, one direction of flow.
 *
 * <p>{@code tags} maps {@code TEXT[]} to a {@code String[]} via {@code SqlTypes.ARRAY}. Hibernate 6
 * has no native mapping for a PostgreSQL array without the explicit type code and
 * {@code columnDefinition}, and this combination was proven to round-trip against a real database
 * before any column depended on it. A join table was rejected: tags are read with the article,
 * never queried across articles, and a second table would buy nothing for an extra join.
 *
 * <p>{@code updatedAt} uses {@code @UpdateTimestamp} rather than the field-initialiser convention
 * the other entities use for their creation timestamps, because it must move on every edit — a
 * stale value here means the re-embedding job silently skips a changed article.
 */
@Entity
@Table(name = "kb_article")
@Getter
@Setter
@NoArgsConstructor
public class KbArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags;

    /** Incremented by the back-office on each substantive edit; drives re-embedding. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /** Unpublished drafts exist in the table but must never be retrieved by the RAG. */
    @Column(name = "published", nullable = false)
    private boolean published = false;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
