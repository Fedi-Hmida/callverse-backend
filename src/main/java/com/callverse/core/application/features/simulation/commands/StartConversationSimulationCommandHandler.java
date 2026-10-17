package com.callverse.core.application.features.simulation.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationRecord;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.application.interfaces.SimulationRunScheduler;
import java.time.Clock;
import java.util.Objects;

/** Creates an isolated simulation run and schedules its bounded dialogue. */
public class StartConversationSimulationCommandHandler {

    private static final int MAX_TURN_LIMIT = 50;

    private final ConversationSimulationStore store;
    private final SimulationRunScheduler scheduler;
    private final Clock clock;

    public StartConversationSimulationCommandHandler(
            ConversationSimulationStore store, SimulationRunScheduler scheduler, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SimulationRecord handle(SimulationSetup setup) {
        validate(setup);
        SimulationRecord started = store.start(setup, clock.instant());
        if (!scheduler.schedule(started.runId(), setup)) {
            store.fail(started.runId(), "Simulation worker rejected the run.", clock.instant());
            return store.find(started.runId()).orElse(started);
        }
        return started;
    }

    private static void validate(SimulationSetup setup) {
        Objects.requireNonNull(setup, "setup must not be null");
        if (setup.scenarioId() == null
                || setup.profile() == null
                || setup.customerSegment() == null
                || setup.strategyCode() == null
                || setup.strategyCode().isBlank()
                || setup.objective() == null
                || setup.objective().isBlank()
                || setup.objective().length() > 1000
                || !Double.isFinite(setup.patience())
                || setup.patience() < 0
                || setup.patience() > 100
                || !Double.isFinite(setup.satisfaction())
                || setup.satisfaction() < 0
                || setup.satisfaction() > 100
                || setup.maxTurns() < 1
                || setup.maxTurns() > MAX_TURN_LIMIT
                || setup.tenureMonths() < 0) {
            throw new InvalidRequestException("Simulation setup contains invalid parameters.");
        }
    }
}
