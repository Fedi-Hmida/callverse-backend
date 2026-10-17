package com.callverse.host.api.dto.response;

import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationRecord;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.RunStatus;
import java.time.Instant;
import java.util.UUID;

/** Progress and terminal result of a conversational simulation run. */
public record ConversationSimulationResponse(
        UUID runId,
        UUID conversationId,
        RunStatus status,
        ConversationStatus conversationStatus,
        long messageCount,
        String errorMessage,
        Instant startedAt,
        Instant endedAt) {

    public static ConversationSimulationResponse from(SimulationRecord record) {
        return new ConversationSimulationResponse(
                record.runId(),
                record.conversationId(),
                record.runStatus(),
                record.conversationStatus(),
                record.messageCount(),
                record.errorMessage(),
                record.startedAt(),
                record.endedAt());
    }
}
