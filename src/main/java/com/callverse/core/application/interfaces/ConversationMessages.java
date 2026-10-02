package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.domain.enums.MessageSender;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A live conversation's transcript.
 *
 * <p>The sender is always decided by the use case from the caller's role, never read from a request
 * body (ownership rule A10), and {@code ai_generated} is always false here: an agent's turn arrives
 * through the AI phase, not through these routes.
 */
public interface ConversationMessages {

    /**
     * Appends a message under the conversation's row lock. An advisor's first message engages an
     * ASSIGNED conversation (ASSIGNED → ACTIVE) in the same transaction.
     *
     * @throws com.callverse.core.domain.exceptions.InvalidStateTransitionException when the state
     *     refuses messages from this sender
     */
    PostedMessage post(UUID conversationId, MessageSender sender, String content, Instant now);

    /** The {@code limit} most recent messages, oldest first. */
    List<MessageRecord> recent(UUID conversationId, int limit);

    /** No sources or tool calls: they are the RAG and XAI trace (ownership rule A9). */
    record MessageRecord(long id, UUID conversationId, MessageSender sender, String content, Instant sentAt) {}

    /**
     * @param conversation the conversation after the message
     * @param statusChanged whether this message moved it (ASSIGNED → ACTIVE)
     */
    record PostedMessage(MessageRecord message, ConversationRecord conversation, boolean statusChanged) {}
}
