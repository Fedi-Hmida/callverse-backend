package com.callverse.host.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of {@code POST /internal/tickets}. <strong>Provisional</strong>, like every {@code /internal}
 * shape. The sizes mirror the columns ({@code category} 30, {@code title} 200) and the severity range
 * mirrors the CHECK constraint, so a bad value is a 400 here rather than a 500 from the database.
 *
 * <p>There is no {@code status} field: a new ticket is always {@code OPEN}. A {@code status} sent
 * anyway is ignored.
 */
@Schema(description = "A support ticket the agent opens for a customer. Provisional.")
public record OpenTicketRequest(
        @NotNull UUID customerId,
        @Schema(nullable = true, description = "When given, must belong to customerId") UUID conversationId,
        @NotBlank @Size(max = 30) @Schema(example = "BILLING") String category,
        @NotBlank @Size(max = 200) @Schema(example = "Double charge on the September invoice") String title,
        @Size(max = 4000) String description,
        @Min(1) @Max(5) @Schema(nullable = true, example = "3", description = "1 to 5, default 3") Integer severity) {}
