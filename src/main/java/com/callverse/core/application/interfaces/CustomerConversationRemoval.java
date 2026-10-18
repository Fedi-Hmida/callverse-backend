package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.domain.enums.ConversationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer removes one of their conversations from their own space, in one transaction: a live
 * conversation is first ended as ABANDONED (an escalated one is refused), a SYSTEM notice is added
 * to the transcript, and the conversation is marked removed. The bank keeps every row.
 */
public interface CustomerConversationRemoval {

    /**
     * @throws com.callverse.core.application.exceptions.ResourceNotFoundException when the
     *     conversation is not this customer's, is simulated, or was already removed
     * @throws com.callverse.core.application.exceptions.ConversationUnderSupervisionException when
     *     it is escalated
     */
    Removal remove(UUID conversationId, UUID customerUserId, String notice, Instant now);

    /**
     * @param statusBefore the status the removal found, so the caller announces the right change
     * @param conversation the conversation after removal; its {@code customerUserId} is null, since
     *     no customer may read it any more
     */
    record Removal(ConversationStatus statusBefore, ConversationRecord conversation, MessageRecord notice) {}
}
