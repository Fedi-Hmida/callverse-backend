package com.callverse.core.application.features.conversation.commands;

import java.util.Objects;
import java.util.UUID;

/** A customer message committed to a live conversation and eligible for an AI response. */
public record AdvisorTurnCommand(UUID conversationId, long triggeringCustomerMessageId) {

    public AdvisorTurnCommand {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        if (triggeringCustomerMessageId <= 0) {
            throw new IllegalArgumentException("triggeringCustomerMessageId must be positive");
        }
    }
}
