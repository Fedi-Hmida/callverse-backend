package com.callverse.core.application.exceptions;

import com.callverse.core.domain.exceptions.DomainException;
import java.util.UUID;

/**
 * The customer already has a live conversation (queued, assigned, active or escalated). One live
 * contact per customer: the AI Advisor and the advisor each work one thread, and a second one would
 * wait in a queue the AI does not answer. The message carries the id to resume or close.
 */
public class ConversationAlreadyOpenException extends DomainException {

    private final UUID openConversationId;

    public ConversationAlreadyOpenException(UUID openConversationId) {
        super("CONVERSATION_ALREADY_OPEN",
                "Conversation %s is still open: close or delete it before opening a new one."
                        .formatted(openConversationId));
        this.openConversationId = openConversationId;
    }

    public UUID openConversationId() {
        return openConversationId;
    }
}
