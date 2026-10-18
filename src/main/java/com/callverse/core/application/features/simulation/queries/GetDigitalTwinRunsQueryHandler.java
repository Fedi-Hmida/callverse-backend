package com.callverse.core.application.features.simulation.queries;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.TwinRunStore;
import com.callverse.core.application.interfaces.TwinRunStore.TwinRun;
import com.callverse.core.application.interfaces.TwinRunStore.TwinScenario;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads digital twin runs: one run, or the completed runs of a scenario side by side, which is the
 * strategy comparison (the same simulated customers under each strategy and seed).
 */
public class GetDigitalTwinRunsQueryHandler {

    private final TwinRunStore store;

    public GetDigitalTwinRunsQueryHandler(TwinRunStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /** The scenarios the twin can replay, by name. */
    public List<TwinScenario> scenarios() {
        return store.scenarios();
    }

    public TwinRun byId(UUID runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return store.find(runId).orElseThrow(() -> new ResourceNotFoundException("Digital twin run", runId));
    }

    public List<TwinRun> comparison(UUID scenarioId) {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        if (store.scenario(scenarioId).isEmpty()) {
            throw new ResourceNotFoundException("Scenario", scenarioId);
        }
        return store.completedForScenario(scenarioId);
    }
}
