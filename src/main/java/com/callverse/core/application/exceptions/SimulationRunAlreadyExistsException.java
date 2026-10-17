package com.callverse.core.application.exceptions;

import com.callverse.core.domain.exceptions.DomainException;
import java.util.UUID;

/** The scenario, strategy and seed already identify a persisted run. */
public class SimulationRunAlreadyExistsException extends DomainException {

    public SimulationRunAlreadyExistsException(UUID scenarioId, String strategyCode, long seed) {
        super(
                "SIMULATION_RUN_ALREADY_EXISTS",
                "A run already exists for scenario %s, strategy %s and seed %d."
                        .formatted(scenarioId, strategyCode, seed));
    }
}
