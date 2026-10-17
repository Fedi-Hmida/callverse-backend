package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import java.util.UUID;

/** Schedules a simulation outside the request thread. */
public interface SimulationRunScheduler {

    boolean schedule(UUID runId, SimulationSetup setup);
}
