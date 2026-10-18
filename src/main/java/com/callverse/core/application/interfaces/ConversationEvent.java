package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import java.time.Instant;
import java.util.UUID;

/**
 * Something happened in one conversation, pushed on {@code /topic/conversation/{id}} to the people
 * allowed to read it: its customer, its advisor, supervisors.
 *
 * <p>{@code MESSAGE_POSTED} carries the message ({@code messageId}, {@code sender}, {@code content},
 * {@code sentAt}, {@code aiGenerated}); {@code STATUS_CHANGED} leaves those null. {@code status} is
 * always the conversation's status after the event. {@code aiGenerated} is an added field: clients
 * that ignore unknown fields are unaffected, so {@link #SCHEMA_VERSION} stays 1.
 */
public record ConversationEvent(
        int schemaVersion,
        Type type,
        Instant occurredAt,
        UUID conversationId,
        ConversationStatus status,
        Long messageId,
        MessageSender sender,
        String content,
        Instant sentAt,
        Boolean aiGenerated) {

    public static final int SCHEMA_VERSION = 1;

    public enum Type {
        MESSAGE_POSTED,
        STATUS_CHANGED
    }

    public static ConversationEvent messagePosted(MessageRecord message, ConversationStatus status) {
        return new ConversationEvent(SCHEMA_VERSION, Type.MESSAGE_POSTED, message.sentAt(), message.conversationId(),
                status, message.id(), message.sender(), message.content(), message.sentAt(), message.aiGenerated());
    }

    public static ConversationEvent statusChanged(UUID conversationId, ConversationStatus status, Instant occurredAt) {
        return new ConversationEvent(SCHEMA_VERSION, Type.STATUS_CHANGED, occurredAt, conversationId, status,
                null, null, null, null, null);
    }
}
