package com.callverse.core.application.features.ticket.commands;

import java.util.UUID;

/**
 * What the agent asks for. Deliberately has no status: that is the backend's decision.
 *
 * @param conversationId optional; when present it must belong to {@code customerId}
 * @param severity optional, 1 to 5; null means the default
 */
public record OpenTicketCommand(
        UUID customerId,
        UUID conversationId,
        String category,
        String title,
        String description,
        Integer severity) {}
