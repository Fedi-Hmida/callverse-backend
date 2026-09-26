package com.callverse.host.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /internal/conversations/{id}/escalate}. <strong>Provisional.</strong> The size
 * mirrors {@code escalation.reason VARCHAR(255)}.
 */
@Schema(description = "Why the agent hands the conversation to a human. Provisional.")
public record EscalateConversationRequest(
        @NotBlank @Size(max = 255) @Schema(example = "Customer asks for a supervisor") String reason) {}
