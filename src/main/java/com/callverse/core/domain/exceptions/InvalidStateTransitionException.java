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
}
