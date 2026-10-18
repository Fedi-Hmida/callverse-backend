package com.callverse.infrastructure.ai;

import com.callverse.core.application.features.simulation.commands.RunConversationSimulationCommandHandler;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.application.interfaces.SimulationRunScheduler;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/** Schedules bounded simulation runs without holding an HTTP request open for LLM calls. */
@Component
class ConversationSimulationDispatcher implements SimulationRunScheduler {

    private static final Logger LOG = System.getLogger(ConversationSimulationDispatcher.class.getName());

    private final RunConversationSimulationCommandHandler handler;
    private final TaskExecutor executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    ConversationSimulationDispatcher(
            RunConversationSimulationCommandHandler handler,
            @Qualifier("conversationSimulationExecutor") TaskExecutor executor) {
        this.handler = handler;
        this.executor = executor;
    }

    @Override
    public boolean schedule(UUID runId, SimulationSetup setup) {
        if (!inFlight.add(runId)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    handler.handle(runId, setup);
                } finally {
                    inFlight.remove(runId);
                }
            });
            return true;
        } catch (TaskRejectedException exception) {
            inFlight.remove(runId);
            LOG.log(Level.WARNING, "Conversation simulation worker rejected run {0}", runId);
            return false;
        }
    }
}
