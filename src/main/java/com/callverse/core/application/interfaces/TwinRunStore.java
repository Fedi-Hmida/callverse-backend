package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.TwinSimulator.TwinKpis;
import com.callverse.core.domain.enums.LoadProfile;
import com.callverse.core.domain.enums.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of digital twin runs: {@code simulation_run} for the run, {@code run_kpi} for its KPIs. */
public interface TwinRunStore {

    Optional<TwinScenario> scenario(UUID scenarioId);

    /** Every scenario, by name: what the twin screen offers to run. */
    List<TwinScenario> scenarios();

    /**
     * Records a RUNNING run.
     *
     * @throws com.callverse.core.application.exceptions.ResourceNotFoundException for an unknown strategy
     * @throws com.callverse.core.application.exceptions.SimulationRunAlreadyExistsException when this
     *     scenario, strategy and seed already ran
     */
    TwinRun start(UUID scenarioId, String strategyCode, long seed, Instant now);

    TwinRun complete(UUID runId, TwinKpis kpis, Instant now);

    TwinRun fail(UUID runId, String safeErrorMessage, Instant now);

    /** @return the run when it is a twin run (not a conversation simulation) */
    Optional<TwinRun> find(UUID runId);

    /** Completed twin runs of a scenario, with their KPIs: the strategy comparison. */
    List<TwinRun> completedForScenario(UUID scenarioId);

    record TwinScenario(UUID id, String name, LoadProfile load, int durationMinutes, int advisorCount) {}

    /** @param kpis null until the run completes */
    record TwinRun(
            UUID runId,
            UUID scenarioId,
            String strategyCode,
            long seed,
            RunStatus status,
            String errorMessage,
            Instant startedAt,
            Instant endedAt,
            RunKpis kpis) {}

    /**
     * What a run keeps: the {@code run_kpi} columns. The simulator also reports served clients, the
     * SLA over served clients and reassignments; the schema has no column for them, so they are
     * returned by the twin but not stored, and every read shows the same stored figures.
     *
     * @param slaRatio answered in time over all arrivals (abandons included)
     */
    record RunKpis(
            int totalConversations,
            double slaRatio,
            double abandonRatio,
            double avgWaitSeconds,
            double p95WaitSeconds) {}
}
