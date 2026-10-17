package com.callverse.core.application.features.quality.queries;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.AiWorkspaceRead;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationRecord;
import com.callverse.core.application.interfaces.ConversationSimulationStore.TranscriptMessage;
import java.util.List;
import java.util.UUID;

/** Called only from supervisor/admin endpoints; live and simulated reads stay separate. */
public class AiWorkspaceQueryHandler {
    private final AiWorkspaceRead read;
    private final ConversationLifecycle conversations;
    private final ConversationSimulationStore simulations;

    public AiWorkspaceQueryHandler(AiWorkspaceRead read, ConversationLifecycle conversations,
                                   ConversationSimulationStore simulations) {
        this.read = read;
        this.conversations = conversations;
        this.simulations = simulations;
    }

    public AiWorkspaceRead.Options options() { return read.options(); }


    public List<SimulationRecord> recentRuns() {
        return read.recentRunIds().stream().flatMap(id -> simulations.find(id).stream()).toList();
    }

    public List<TranscriptMessage> transcript(UUID runId) {
        SimulationRecord run = simulations.find(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Simulation run", runId));
        return simulations.transcript(run.conversationId());
    }

    public List<AiWorkspaceRead.QualityView> quality(UUID conversationId) {
        conversations.find(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
        return read.quality(conversationId);
    }
}
