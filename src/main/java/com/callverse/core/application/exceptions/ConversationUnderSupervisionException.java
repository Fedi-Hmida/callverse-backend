package com.callverse.core.application.exceptions;

import com.callverse.core.domain.exceptions.DomainException;
import java.util.UUID;

/** A supervisor is handling the conversation: the customer may remove it once it is closed. */
public class ConversationUnderSupervisionException extends DomainException {

    public ConversationUnderSupervisionException(UUID conversationId) {
        super("CONVERSATION_UNDER_SUPERVISION",
                "Conversation %s is being handled by a supervisor and can be deleted once it is closed."
                        .formatted(conversationId));
    }
}
