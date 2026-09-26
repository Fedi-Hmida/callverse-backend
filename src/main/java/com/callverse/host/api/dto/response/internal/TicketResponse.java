package com.callverse.host.api.dto.response.internal;

import com.callverse.core.application.interfaces.Tickets.OpenedTicket;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Tool response for {@code POST /internal/tickets}. <strong>Provisional.</strong> */
@Schema(description = "The ticket as created. Provisional.")
public record TicketResponse(
        UUID id,
        UUID customerId,
        @Schema(nullable = true) UUID conversationId,
        @Schema(example = "BILLING") String category,
        String title,
        @Schema(example = "3") int severity,
        @Schema(example = "OPEN") String status,
        Instant createdAt) {

    public static TicketResponse from(OpenedTicket ticket) {
        return new TicketResponse(
                ticket.id(),
                ticket.customerId(),
                ticket.conversationId(),
                ticket.category(),
                ticket.title(),
                ticket.severity(),
                ticket.status().name(),
                ticket.createdAt());
    }
}
