package com.callverse.core.application.exceptions;

import java.util.UUID;

/**
 * A write names a customer and a conversation that belongs to someone else.
 *
 * <p>The frozen tool contract lets the agent state the customer id, and nothing in the route ties it
 * to the conversation being handled ({@code OWNERSHIP_RULES.md} E5). When the agent also names the
 * conversation, this is the check that the two agree — without it, any caller of the ticket use case
 * could file tickets on any customer while appearing to act inside a legitimate conversation.
 */
public class ConversationCustomerMismatchException extends ApplicationException {

    private static final String CODE = "CONVERSATION_CUSTOMER_MISMATCH";

    public ConversationCustomerMismatchException(UUID conversationId) {
        super(CODE, "Conversation '%s' does not belong to the given customer".formatted(conversationId));
    }
}
