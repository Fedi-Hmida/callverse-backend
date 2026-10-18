package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorResponse;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorRequest;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorResponse;
import com.callverse.core.domain.enums.AgentType;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.RunStatus;
import com.callverse.core.domain.enums.SimulatorProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for isolated Simulator → Advisor dialogue runs. */
public interface ConversationSimulationStore {

    SimulationRecord start(SimulationSetup setup, Instant now);

    Optional<SimulationRecord> find(UUID runId);

    List<TranscriptMessage> transcript(UUID conversationId);

    TranscriptMessage appendCustomerMessage(UUID conversationId, String content, Instant now);

    void appendAdvisorMessage(UUID conversationId, AdvisorResponse response, Instant now);

    void recordSimulatorDecision(UUID runId, int simTime, SimulatorRequest observation, SimulatorResponse action);

    void recordAdvisorDecision(UUID runId, int simTime, String message, AdvisorResponse action);

    void complete(UUID runId, UUID conversationId, ConversationStatus conversationStatus, Instant now);

    void fail(UUID runId, String safeErrorMessage, Instant now);

    record SimulationSetup(
            UUID scenarioId,
            String strategyCode,
            long seed,
            SimulatorProfile profile,
            String objective,
            double patience,
            double satisfaction,
            int maxTurns,
            CustomerSegment customerSegment,
            int tenureMonths) {}

    record TranscriptMessage(long id, com.callverse.core.domain.enums.MessageSender sender, String content, Instant sentAt) {}

    record SimulationRecord(
            UUID runId,
            UUID conversationId,
            UUID customerId,
            RunStatus runStatus,
            ConversationStatus conversationStatus,
            long messageCount,
            String errorMessage,
            Instant startedAt,
            Instant endedAt) {}
}
