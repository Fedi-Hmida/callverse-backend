package com.callverse.core.application.features.simulation.commands;

import java.util.Objects;
import java.util.UUID;

/** Replay a scenario under a control strategy, with a seed that makes the run reproducible. */
public record RunDigitalTwinCommand(UUID scenarioId, String strategyCode, long seed) {

    public RunDigitalTwinCommand {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(strategyCode, "strategyCode must not be null");
    }
}
