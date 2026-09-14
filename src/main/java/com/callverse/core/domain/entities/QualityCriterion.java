package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One dimension of the conversation quality grid. Maps {@code quality_criterion}.
 *
 * <p>The grid is configurable data rather than code, which is why criteria are rows and scores are
 * JSONB on {@link QualityEvaluation} rather than one column per criterion. Adding "proactivity"
 * later must not require a migration and a backfill of every historical evaluation.
 *
 * <p>{@code weight} values across active criteria are expected to sum to 1.000, so that
 * {@link QualityEvaluation#getGlobalScore()} is comparable between conversations. Nothing in the
 * schema enforces that sum — it is asserted in the persistence test against the seeded grid, and
 * would need re-checking whenever a criterion is added or deactivated.
 */
@Entity
@Table(name = "quality_criterion")
@Getter
@Setter
@NoArgsConstructor
public class QualityCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** Used as the key inside {@code quality_evaluation.scores}, so it is effectively a contract. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "weight", nullable = false, precision = 4, scale = 3)
    private BigDecimal weight;

    /** Deactivated criteria stay in the table: historical evaluations still reference their codes. */
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
