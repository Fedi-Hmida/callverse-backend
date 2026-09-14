package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The computed outcome of one simulation run. Maps {@code run_kpi}.
 *
 * <p><strong>Shared primary key.</strong> {@code run_id} is simultaneously this table's primary key
 * and a foreign key to {@link SimulationRun}, expressed with {@code @MapsId}: the identifier is not
 * generated here but taken from the run. That enforces one-KPI-row-per-run in the schema rather than
 * by convention, and makes the join free.
 *
 * <p>Every metric is nullable because a KPI row may be inserted when the run starts and filled as
 * the computation completes, and because a FAILED run has some measures and not others.
 *
 * <p><strong>This table is what every comparison chart reads.</strong> Recomputing service level or
 * p95 wait from {@code conversation} and {@code metric_sample} each time a report is opened would
 * scan hundreds of thousands of rows for numbers that cannot change once a run has ended. They are
 * computed once, here.
 *
 * <p>{@code fairnessRatio} deserves a note: it measures how evenly work was spread across advisors.
 * An RL policy that maximises service level by overloading the three fastest advisors would score
 * well on {@code slaRatio} and badly here, which is exactly the trade-off the experiment needs to
 * make visible rather than hide.
 */
@Entity
@Table(name = "run_kpi")
@Getter
@Setter
@NoArgsConstructor
public class RunKpi {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID runId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private SimulationRun run;

    @Column(name = "total_conversations")
    private Integer totalConversations;

    @Column(name = "avg_wait_seconds", precision = 8, scale = 2)
    private BigDecimal avgWaitSeconds;

    /** p95 rather than max: one outlier should not define the tail. */
    @Column(name = "p95_wait_seconds", precision = 8, scale = 2)
    private BigDecimal p95WaitSeconds;

    /** The headline measure, compared against {@link SlaPolicy#getTargetRatio()}. */
    @Column(name = "sla_ratio", precision = 5, scale = 4)
    private BigDecimal slaRatio;

    @Column(name = "abandon_ratio", precision = 5, scale = 4)
    private BigDecimal abandonRatio;

    @Column(name = "avg_handle_seconds", precision = 8, scale = 2)
    private BigDecimal avgHandleSeconds;

    /** Fraction of staffed time spent handling. Too high is burnout, too low is waste. */
    @Column(name = "occupancy_ratio", precision = 5, scale = 4)
    private BigDecimal occupancyRatio;

    @Column(name = "estimated_cost", precision = 10, scale = 2)
    private BigDecimal estimatedCost;

    /** Mean quality score from the Quality Analyst agent across the run's conversations. */
    @Column(name = "avg_quality_score", precision = 4, scale = 2)
    private BigDecimal avgQualityScore;

    /** Evenness of workload across advisors. See the class javadoc. */
    @Column(name = "fairness_ratio", precision = 6, scale = 3)
    private BigDecimal fairnessRatio;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();
}
