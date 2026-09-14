package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.EvaluatorType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A scored assessment of one conversation. Maps {@code quality_evaluation}.
 *
 * <p><strong>{@code evaluator} is what makes the agreement study possible.</strong> AI evaluations
 * from the Quality Analyst agent and the 30-50 human annotations live in the same table
 * distinguished only by this column, so inter-rater agreement — Cohen's kappa — is a self-join
 * rather than a reconciliation between two schemas. An agent that scores conversations is only
 * credible if its scores track a human's, and this table is where that is demonstrated rather than
 * asserted.
 *
 * <p>{@code scores} is JSONB keyed by {@link QualityCriterion#getCode()} because the grid is
 * configurable; see that class for why there is deliberately no column per criterion.
 *
 * <p>{@code flags} records objective defects rather than scores — for example
 * {@code {"unsourced_claims": 2}}, a count of assertions the agent made without a retrieved source
 * backing them. That is a hallucination measure, and unlike a quality score it is checkable.
 */
@Entity
@Table(name = "quality_evaluation")
@Getter
@Setter
@NoArgsConstructor
public class QualityEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    /** Weighted aggregate of {@code scores}, comparable across conversations. */
    @Column(name = "global_score", nullable = false, precision = 4, scale = 2)
    private BigDecimal globalScore;

    /** {@code {criterion_code: score}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scores", nullable = false)
    private JsonNode scores;

    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    /** Objective defects, e.g. {@code {"unsourced_claims": 2}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flags")
    private JsonNode flags;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluator", nullable = false, length = 20)
    private EvaluatorType evaluator;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
