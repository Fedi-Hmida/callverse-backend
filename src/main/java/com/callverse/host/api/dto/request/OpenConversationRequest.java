package com.callverse.host.api.dto.request;

import com.callverse.core.domain.enums.Intent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** Body of {@code POST /api/v1/conversations}. No status, score or advisor: the server decides those. */
@Schema(name = "OpenConversationRequest")
public record OpenConversationRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID customerId,
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,30}", message = "must be a skill code")
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "CARDS")
                String skill,
        @Schema(description = "What the contact is about, when known", example = "FRAUD") Intent intent) {}
