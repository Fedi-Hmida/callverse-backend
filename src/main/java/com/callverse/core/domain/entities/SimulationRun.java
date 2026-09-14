package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.RunStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One execution of a scenario under a strategy with a fixed seed. Maps {@code simulation_run}.
 * Aggregate root of the experiment universe.
 *
 * <p><strong>{@code uq_run} on (scenario, strategy, seed) is the scientific control.</strong> It
 * guarantees a given triple can exist only once, which is what makes a result reproducible rather
 * than merely repeated: re-running the same configuration cannot silently produce a second,
 * different row that someone later averages in. That constraint is why the comparison between a
 * baseline and an RL policy is defensible at all, and it is asserted in the persistence test rather
 * than assumed.
 *
 * <p>{@code FAILED} runs are kept, not deleted. A strategy that crashes under saturation is itself
 * a finding, and {@code errorMessage} is where the evidence lives.
 *
 * <p>{@link RunKpi} is reached through this root via a shared primary key; it has no repository of
 * its own.
 */
@Entity
@Table(
        name = "simulation_run",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_run",
                        columnNames = {"scenario_id", "strategy_id", "seed"}))
@Getter
@Setter
@NoArgsConstructor
public class SimulationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false)
    private Scenario scenario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "strategy_id", nullable = false)
    private ControlStrategy strategy;

    /** The RNG seed. Together with scenario and strategy it identifies the run uniquely. */
    @Column(name = "seed", nullable = false)
    private long seed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RunStatus status = RunStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** Populated on FAILED. A crash under load is a result, so the run is kept and annotated. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /**
     * Inside this aggregate, sharing this row's primary key. Bidirectional and justified: the KPI
     * row is what every comparison chart reads, and it has no repository, so it must be reachable
     * from the run.
     */
    @OneToOne(mappedBy = "run", fetch = FetchType.LAZY)
    private RunKpi kpi;
}
