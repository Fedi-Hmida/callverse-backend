package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.Tickets.OpenedTicket;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** A ticket as it was stored: always OPEN at creation, whatever the caller sent. */
@Schema(name = "TicketResponse", description = "A support ticket")
public record TicketResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) UUID customerId,
        @Schema(nullable = true) UUID conversationId,
        @Schema(requiredMode = REQUIRED, example = "FRAUD") String category,
        @Schema(requiredMode = REQUIRED, example = "Paiements inconnus a l'etranger") String title,
        @Schema(requiredMode = REQUIRED, description = "1 (most severe) to 5", example = "2") int severity,
        @Schema(requiredMode = REQUIRED, example = "OPEN") String status,
        @Schema(requiredMode = REQUIRED) Instant createdAt) {

    public static TicketResponse from(OpenedTicket t) {
        return new TicketResponse(
                t.id(), t.customerId(), t.conversationId(), t.category(), t.title(), t.severity(),
                t.status().name(), t.createdAt());
    }
}
