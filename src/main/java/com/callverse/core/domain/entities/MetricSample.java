package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A point-in-time reading of one skill's queue during a simulation run. Maps
 * {@code metric_sample}.
 *
 * <h2>This entity is deliberately read-only. Do not make it writable.</h2>
 *
 * <p>It is annotated {@link Immutable} and has no setters, and both are load-bearing rather than
 * stylistic.
 *
 * <p><strong>The volumetry.</strong> A 60-minute run sampled across 3 skills is 10,800 rows at
 * one-second granularity. The full experimental matrix — 3 load profiles x 4 strategies x 5 seeds,
 * 60 runs — is on the order of 650,000 rows. Three consequences follow, and all three are already
 * decided:
 *
 * <ol>
 *   <li>Sample every <strong>10 seconds</strong>, not every second.
 *   <li>Write in <strong>batches of 500</strong> through
 *       {@code com.callverse.core.application.interfaces.MetricSampleBatchWriter}, backed by
 *       {@code JdbcTemplate.batchUpdate}.
 *   <li>Reinforcement-learning <strong>training</strong> runs, which number in the thousands of
 *       episodes, persist <strong>nothing</strong>. Only evaluation runs reach this table.
 * </ol>
 *
 * <p><strong>Why not just use {@code JpaRepository.saveAll}.</strong> It is not a shortcut, it is a
 * defect. {@code saveAll} issues one INSERT per row through the persistence context, accumulating
 * 650,000 managed entities and paying a network round trip each. Against Neon, where storage is
 * separated from compute and write latency is correspondingly higher, that turns a batch that should
 * take seconds into one that takes many minutes and may exhaust the connection.
 *
 * <p>The guard is structural rather than advisory: {@code MetricSampleRepository} extends Spring
 * Data's bare {@code Repository} rather than {@code JpaRepository}, so {@code save} and
 * {@code saveAll} do not exist on the type and the compiler refuses the mistake.
 *
 * <p>This class therefore exists for one purpose: reading samples back to plot a run's queue
 * behaviour over time.
 */
@Entity
@Table(name = "metric_sample")
@Immutable
@Getter
@NoArgsConstructor
public class MetricSample {

    @EmbeddedId
    private MetricSampleId id;

    @MapsId("runId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private SimulationRun run;

    @MapsId("skillId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    /** Conversations waiting for this skill at this instant. The primary RL observation. */
    @Column(name = "queue_length")
    private Integer queueLength;

    @Column(name = "avg_wait", precision = 8, scale = 2)
    private BigDecimal avgWait;

    @Column(name = "available_count")
    private Integer availableCount;

    @Column(name = "busy_count")
    private Integer busyCount;
}
