package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.SimulationRun;
import com.callverse.core.domain.enums.RunStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Root of the experiment universe. {@code RunKpi} is reached through the run via its shared primary
 * key and has no repository of its own.
 */
@Repository
public interface SimulationRunRepository extends JpaRepository<SimulationRun, UUID> {

    /**
     * Backed by {@code uq_run}. Checking before launching is what makes a re-run idempotent instead
     * of a constraint violation, and the constraint is what makes results reproducible rather than
     * merely repeated.
     */
    Optional<SimulationRun> findByScenarioIdAndStrategyIdAndSeed(
            UUID scenarioId, UUID strategyId, long seed);

    boolean existsByScenarioIdAndStrategyIdAndSeed(UUID scenarioId, UUID strategyId, long seed);

    List<SimulationRun> findByStatus(RunStatus status);

    /**
     * The comparison query the whole project builds towards: every completed run of one scenario,
     * with its KPI row, so a baseline strategy can be set against a reinforcement-learning one.
     *
     * <p>{@code join fetch} is deliberate. Without it this is a textbook N+1 — one query for the
     * runs, then one per run for its KPI — and the comparison table on the report page would issue
     * sixty round trips to Neon to render one grid.
     */
    @Query("""
           select r
             from SimulationRun r
             join fetch r.kpi
             join fetch r.strategy
            where r.scenario.id = :scenarioId
              and r.status = com.callverse.core.domain.enums.RunStatus.COMPLETED
            order by r.strategy.code asc, r.seed asc
           """)
    List<SimulationRun> findCompletedWithKpiByScenario(@Param("scenarioId") UUID scenarioId);
}
