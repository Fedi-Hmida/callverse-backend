package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.ConversationStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Looks up a conversation's identity, owner and state — what a write tool needs to check a claim
 * against before acting.
 */
public interface ConversationDirectory {

    Optional<ConversationRef> find(UUID conversationId);

    /**
     * @param customerId the customer the conversation belongs to — what a ticket's claimed customer
     *     is checked against
     * @param status the current state, which decides whether an escalation is allowed
     * @param advisorUserId the login behind the assigned advisor; null while unassigned
     */
    record ConversationRef(UUID id, UUID customerId, ConversationStatus status, UUID advisorUserId) {}
}
