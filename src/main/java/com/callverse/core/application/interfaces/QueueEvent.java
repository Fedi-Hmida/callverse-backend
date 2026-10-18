package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.ConversationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * An arrival in or a departure from a skill queue, pushed on {@code /topic/queue/{skill}}, and the
 * customer's removal of a conversation of that skill, so every advisor holding it hears of it even
 * when that conversation is not the one on screen.
 *
 * <p>Identifiers only: no customer name, no priority score. The advisor's screen reloads the queue
 * figures from {@code waiting}, and opens a conversation only once it has taken it.
 *
 * @param status QUEUED for an arrival; ASSIGNED or ABANDONED for a departure; the status after
 *     removal for {@code CONVERSATION_DELETED_BY_CUSTOMER}
 * @param waiting how many conversations wait in this queue after the change
 */
public record QueueEvent(
        int schemaVersion,
        Type type,
        Instant occurredAt,
        String skill,
        UUID conversationId,
        ConversationStatus status,
        long waiting) {

    public static final int SCHEMA_VERSION = 1;

    public enum Type {
        CONVERSATION_QUEUED,
        CONVERSATION_LEFT_QUEUE,
        CONVERSATION_DELETED_BY_CUSTOMER
    }
}
