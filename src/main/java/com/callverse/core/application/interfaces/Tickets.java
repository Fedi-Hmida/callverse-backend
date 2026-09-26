package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.TicketStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes support tickets. Stores what it is given: every decision — which customer, which status,
 * which severity — is taken by the handler before this port is called.
 */
public interface Tickets {

    OpenedTicket open(NewTicket ticket);

    /** @param conversationId may be null: a ticket need not come from a conversation */
    record NewTicket(
            UUID customerId,
            UUID conversationId,
            String category,
            String title,
            String description,
            int severity,
            TicketStatus status) {}

    record OpenedTicket(
            UUID id,
            UUID customerId,
            UUID conversationId,
            String category,
            String title,
            int severity,
            TicketStatus status,
            Instant createdAt) {}
}
