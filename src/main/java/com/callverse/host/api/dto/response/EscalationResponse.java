package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.Escalations.EscalationRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** An escalation to a supervisor. */
@Schema(name = "EscalationResponse", description = "An escalation of a conversation to a supervisor")
public record EscalationResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) UUID conversationId,
        @Schema(requiredMode = REQUIRED) String reason,
        @Schema(requiredMode = REQUIRED, example = "ADVISOR", allowableValues = {"ADVISOR", "AI", "RULE"})
                String raisedBy,
        @Schema(requiredMode = REQUIRED, example = "PENDING") String status,
        @Schema(requiredMode = REQUIRED) Instant createdAt) {

    public static EscalationResponse from(EscalationRecord e) {
        return new EscalationResponse(
                e.id(), e.conversationId(), e.reason(), e.raisedBy().name(), e.status().name(), e.createdAt());
    }
}
