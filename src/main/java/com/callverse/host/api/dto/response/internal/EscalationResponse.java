package com.callverse.host.api.dto.response.internal;

import com.callverse.core.application.interfaces.Escalations.EscalationRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Tool response for {@code POST /internal/conversations/{id}/escalate}. <strong>Provisional.</strong>
 * The same shape whether the escalation was just created (201) or already pending (200).
 */
@Schema(description = "The conversation's pending escalation. Provisional.")
public record EscalationResponse(
        UUID id,
        UUID conversationId,
        String reason,
        @Schema(example = "AI") String raisedBy,
        @Schema(example = "PENDING") String status,
        Instant createdAt) {

    public static EscalationResponse from(EscalationRecord escalation) {
        return new EscalationResponse(
                escalation.id(),
                escalation.conversationId(),
                escalation.reason(),
                escalation.raisedBy().name(),
                escalation.status().name(),
                escalation.createdAt());
    }
}
