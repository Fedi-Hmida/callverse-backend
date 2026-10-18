package com.callverse.host.api.dto.request.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request body for the service-key-protected ticket tool. */
@Schema(name = "InternalOpenTicketRequest")
public record InternalOpenTicketRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID customerId,
        @NotNull
                @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Must identify a conversation owned by customerId")
                UUID conversationId,
        @NotBlank
                @Size(max = 30)
                @Pattern(regexp = "BALANCE|CARD|CREDIT|FRAUD|ACCOUNT_CLOSURE|ACCOUNTS|CARDS|OTHER")
                @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        allowableValues = {
                            "BALANCE", "CARD", "CREDIT", "FRAUD", "ACCOUNT_CLOSURE", "ACCOUNTS", "CARDS", "OTHER"
                        })
                String category,
        @NotBlank @Size(max = 200) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Size(max = 4000) @Schema(nullable = true) String description,
        @Min(1) @Max(5) @Schema(nullable = true, description = "1 to 5, default 3") Integer severity) {}
