package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.exceptions.SimulationRunAlreadyExistsException;
import com.callverse.core.application.interfaces.TwinRunStore;
import com.callverse.core.application.interfaces.TwinSimulator.TwinKpis;
import com.callverse.core.domain.entities.ControlStrategy;
import com.callverse.core.domain.entities.RunKpi;
import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.entities.SimulationRun;
import com.callverse.core.domain.enums.RunStatus;
import com.callverse.infrastructure.persistence.repositories.ControlStrategyRepository;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import com.callverse.infrastructure.persistence.repositories.ScenarioRepository;
import com.callverse.infrastructure.persistence.repositories.SimulationRunRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Digital twin runs in the existing schema: the run in {@code simulation_run}, its KPIs in
 * {@code run_kpi} (rounded to the column precision). A twin run has no conversation, which is what
 * tells it apart from a conversation simulation sharing the same table.
 */
@Component
@RequiredArgsConstructor
class TwinRunStoreAdapter implements TwinRunStore {

    private final ScenarioRepository scenarios;
    private final ControlStrategyRepository strategies;
    private final SimulationRunRepository runs;
    private final ConversationRepository conversations;
    private final EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public Optional<TwinScenario> scenario(UUID scenarioId) {
        return scenarios.findById(scenarioId).map(scenario -> new TwinScenario(
                scenario.getId(), scenario.getName(), scenario.getLoadProfile(),
                scenario.getDurationMinutes(), scenario.getAdvisorCount()));
    }

    @Override
    @Transactional
    public TwinRun start(UUID scenarioId, String strategyCode, long seed, Instant now) {
        Scenario scenario = scenarios.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario", scenarioId));
        ControlStrategy strategy = strategies.findByCode(strategyCode)
                .orElseThrow(() -> new ResourceNotFoundException("Control strategy", strategyCode));
        if (runs.existsByScenarioIdAndStrategyIdAndSeed(scenario.getId(), strategy.getId(), seed)) {
            throw new SimulationRunAlreadyExistsException(scenario.getId(), strategy.getCode(), seed);
        }
        SimulationRun run = new SimulationRun();
        run.setScenario(scenario);
        run.setStrategy(strategy);
        run.setSeed(seed);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(micros(now));
        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            throw new SimulationRunAlreadyExistsException(scenario.getId(), strategy.getCode(), seed);
        }
        return toRun(run, null);
    }

    @Override
    @Transactional
    public TwinRun complete(UUID runId, TwinKpis kpis, Instant now) {
        SimulationRun run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Run", runId));
        RunKpi kpi = new RunKpi();
        kpi.setRun(run);
        kpi.setTotalConversations(kpis.arrived());
        kpi.setSlaRatio(scaled(kpis.slaAllArrivals(), 4));
        kpi.setAbandonRatio(scaled(kpis.abandonRate(), 4));
        kpi.setAvgWaitSeconds(scaled(kpis.avgWaitSeconds(), 2));
        kpi.setP95WaitSeconds(scaled(kpis.p95WaitSeconds(), 2));
        kpi.setComputedAt(micros(now));
        em.persist(kpi);
        run.setStatus(RunStatus.COMPLETED);
        run.setEndedAt(micros(now));
        return toRun(run, kpi);
    }

    @Override
    @Transactional
    public TwinRun fail(UUID runId, String safeErrorMessage, Instant now) {
        SimulationRun run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Run", runId));
        run.setStatus(RunStatus.FAILED);
        run.setEndedAt(micros(now));
        run.setErrorMessage(safeErrorMessage);
        return toRun(run, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TwinRun> find(UUID runId) {
        return runs.findById(runId)
                .filter(run -> conversations.findByRunId(runId).isEmpty())
                .map(run -> toRun(run, run.getKpi()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TwinRun> completedForScenario(UUID scenarioId) {
        // Conversation simulations never get a run_kpi row, so the KPI join keeps twin runs only.
        return runs.findCompletedWithKpiByScenario(scenarioId).stream()
                .map(run -> toRun(run, run.getKpi()))
                .toList();
    }

    private static TwinRun toRun(SimulationRun run, RunKpi kpi) {
        return new TwinRun(
                run.getId(),
                run.getScenario().getId(),
                run.getStrategy().getCode(),
                run.getSeed(),
                run.getStatus(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getEndedAt(),
                kpi == null ? null : new RunKpis(
                        kpi.getTotalConversations(),
                        kpi.getSlaRatio().doubleValue(),
                        kpi.getAbandonRatio().doubleValue(),
                        kpi.getAvgWaitSeconds().doubleValue(),
                        kpi.getP95WaitSeconds().doubleValue()));
    }

    private static BigDecimal scaled(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
