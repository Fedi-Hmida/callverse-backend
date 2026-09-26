package com.callverse.core.domain.exceptions;

import java.util.UUID;

/**
 * An attempt to assign a conversation that already has an advisor.
 *
 * <p>A 409, not a 400: assignment is a race, and losing it is a legitimate outcome rather than a
 * malformed request. Two advisors pulling from the same skill queue at the same moment is the
 * expected case, not the exceptional one — the queue engine in Phase 4 will rely on exactly this
 * distinction to let the loser retry against the next conversation instead of failing the operator.
 *
 * <p>Named in the inter-repository API contract. Thrown by the queue engine in Phase 4.
 */
public class ConversationAlreadyAssignedException extends DomainException {

    private static final String CODE = "CONVERSATION_ALREADY_ASSIGNED";

    public ConversationAlreadyAssignedException(UUID conversationId, UUID assignedAdvisorId) {
        super(
                CODE,
                "Conversation %s is already assigned to advisor %s."
                        .formatted(conversationId, assignedAdvisorId));
    }
}
