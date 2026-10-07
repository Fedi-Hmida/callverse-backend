package com.callverse.host.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/conversations/{id}/escalations}. */
@Schema(name = "EscalationRequest")
public record EscalationRequest(
        @NotBlank @Size(max = 255)
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Fraude suspectee, besoin d'un superviseur")
                String reason) {}
