package com.callverse.host.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/tickets}. There is no status field: every ticket is created OPEN, and
 * a status sent by the caller is not part of the contract.
 */
@Schema(name = "OpenTicketRequest")
public record OpenTicketRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID customerId,
        @Schema(nullable = true, description = "When given, must belong to customerId") UUID conversationId,
        @NotBlank @Size(max = 30) @Pattern(regexp = "[A-Z][A-Z_]*", message = "must be an upper-case code")
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "FRAUD")
                String category,
        @NotBlank @Size(max = 200)
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Paiements inconnus a l'etranger")
                String title,
        @Size(max = 4000) @Schema(nullable = true) String description,
        @Min(1) @Max(5) @Schema(nullable = true, example = "3", description = "1 to 5, default 3") Integer severity) {}
