package com.callverse.core.application.features.simulation.queries;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationRecord;
import java.util.Objects;
import java.util.UUID;

/** Reads the progress and result of a conversational simulation run. */
public class GetConversationSimulationQueryHandler {

    private final ConversationSimulationStore store;

    public GetConversationSimulationQueryHandler(ConversationSimulationStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public SimulationRecord handle(UUID runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return store.find(runId).orElseThrow(() -> new ResourceNotFoundException("Simulation run", runId));
    }
}
