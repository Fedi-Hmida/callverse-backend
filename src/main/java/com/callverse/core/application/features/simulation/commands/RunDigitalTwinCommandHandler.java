package com.callverse.core.application.features.simulation.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.exceptions.WorkforceServiceUnavailableException;
import com.callverse.core.application.interfaces.TwinRunStore;
import com.callverse.core.application.interfaces.TwinRunStore.TwinRun;
import com.callverse.core.application.interfaces.TwinRunStore.TwinScenario;
import com.callverse.core.application.interfaces.TwinSimulator;
import com.callverse.core.application.interfaces.TwinSimulator.TwinKpis;
import com.callverse.core.application.interfaces.TwinSimulator.TwinRunSpec;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Runs one digital twin day: the scenario's load, duration and staff, played under a control
 * strategy with a seed, and keeps the KPIs for the strategy comparison.
 *
 * <p>The scenario's advisor count is spread over the four skills (at least one each), the size the
 * simulated centre models per skill. The call is synchronous: an eight-hour day simulates in well
 * under a second. A failed call ends the run FAILED with a generic message; it is never left RUNNING.
 * The same scenario, strategy and seed cannot run twice ({@code uq_run}): the result is reproducible,
 * so a repeat would only duplicate it.
 */
public class RunDigitalTwinCommandHandler {

    private static final Logger LOG = System.getLogger(RunDigitalTwinCommandHandler.class.getName());
    private static final int SKILLS = 4;
    static final String FAILURE = "Digital twin service unavailable or returned an invalid result.";

    /** Control strategy codes (control_strategy.code) to the twin's strategy names. */
    private static final Map<String, String> STRATEGIES =
            Map.of("STATIC_FIFO", "STATIC_FIFO", "THRESHOLD", "THRESHOLD", "RL_PPO_V1", "PPO");

    private final TwinRunStore store;
    private final TwinSimulator simulator;
    private final Clock clock;

    public RunDigitalTwinCommandHandler(TwinRunStore store, TwinSimulator simulator, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.simulator = Objects.requireNonNull(simulator, "simulator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public TwinRun handle(RunDigitalTwinCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.seed() < 0) {
            throw new InvalidRequestException("seed must be 0 or more");
        }
        TwinScenario scenario = store.scenario(command.scenarioId())
                .orElseThrow(() -> new ResourceNotFoundException("Scenario", command.scenarioId()));
        String strategy = STRATEGIES.get(command.strategyCode());
        if (strategy == null) {
            throw new InvalidRequestException(
                    "The digital twin plays STATIC_FIFO, THRESHOLD or RL_PPO_V1, not " + command.strategyCode());
        }

        TwinRun run = store.start(scenario.id(), command.strategyCode(), command.seed(), clock.instant());
        TwinKpis kpis;
        try {
            kpis = simulator.run(new TwinRunSpec(
                    strategy,
                    command.seed(),
                    scenario.load(),
                    scenario.durationMinutes(),
                    Math.max(1, scenario.advisorCount() / SKILLS)));
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "Digital twin run {0} failed: {1}",
                    run.runId(), exception.getClass().getSimpleName());
            store.fail(run.runId(), FAILURE, clock.instant());
            throw new WorkforceServiceUnavailableException(FAILURE, exception);
        }
        return store.complete(run.runId(), kpis, clock.instant());
    }
}
