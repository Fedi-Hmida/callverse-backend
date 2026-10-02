package com.callverse.core.domain.exceptions;

import com.callverse.core.domain.enums.ConversationStatus;

/**
 * An operation would move a conversation along an edge its state machine does not have — for
 * example escalating one that is already resolved. {@code INVALID_STATE_TRANSITION} is one of the
 * business codes the project context names; it maps to 409, because the request is well-formed and
 * it is the conversation's current state that forbids it.
 */
public class InvalidStateTransitionException extends DomainException {

    private static final String CODE = "INVALID_STATE_TRANSITION";

    public InvalidStateTransitionException(ConversationStatus from, ConversationStatus to) {
        super(CODE, "A conversation in state %s cannot move to %s".formatted(from, to));
    }

    /** The same refusal for any other state machine, named by {@code subject} ("card", ...). */
    public InvalidStateTransitionException(String subject, Enum<?> from, Enum<?> to) {
        super(CODE, "A %s in state %s cannot move to %s".formatted(subject, from, to));
    }

    private InvalidStateTransitionException(String message) {
        super(CODE, message);
    }

    /**
     * A message refused by the conversation's state: ownership rule A10 names this code for a
     * message posted into a closed conversation.
     */
    public static InvalidStateTransitionException noMessagesIn(ConversationStatus status, String sender) {
        return new InvalidStateTransitionException(
                "A conversation in state %s accepts no %s message".formatted(status, sender));
    }
}
