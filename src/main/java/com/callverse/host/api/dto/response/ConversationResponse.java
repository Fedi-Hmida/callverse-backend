package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A conversation as a client sees it. Never the priority score, the wait, the handle time or the SLA
 * flag: those are internal operating data (ownership rule A7), read by supervision as aggregates.
 */
@Schema(name = "Conversation")
public record ConversationResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) UUID customerId,
        @Schema(description = "Null while the conversation waits in its queue") UUID advisorId,
        @Schema(requiredMode = REQUIRED, example = "CARDS") String skill,
        @Schema(example = "FRAUD") String intent,
        @Schema(requiredMode = REQUIRED, example = "CHAT") String channel,
        @Schema(requiredMode = REQUIRED, example = "ACTIVE",
                allowableValues = {"QUEUED", "ASSIGNED", "ACTIVE", "ESCALATED", "RESOLVED", "ABANDONED"})
                String status,
        @Schema(requiredMode = REQUIRED) Instant queuedAt,
        Instant assignedAt,
        Instant endedAt) {

    public static ConversationResponse from(ConversationRecord c) {
        return new ConversationResponse(c.id(), c.customerId(), c.advisorId(), c.skill(),
                c.intent() == null ? null : c.intent().name(), c.channel().name(), c.status().name(),
                c.queuedAt(), c.assignedAt(), c.endedAt());
    }

    /** A list of conversations, wrapped so that fields can be added without breaking clients. */
    @Schema(name = "ConversationList")
    public record ListResponse(@Schema(requiredMode = REQUIRED) List<ConversationResponse> conversations) {

        public static ListResponse from(List<ConversationRecord> records) {
            return new ListResponse(records.stream().map(ConversationResponse::from).toList());
        }
    }
}
