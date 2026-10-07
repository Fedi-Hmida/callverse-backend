package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One turn of a conversation. No sources or tool calls: they are the RAG and XAI trace (rule A9). */
@Schema(name = "Message")
public record MessageResponse(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) UUID conversationId,
        @Schema(requiredMode = REQUIRED, example = "ADVISOR", allowableValues = {"CUSTOMER", "ADVISOR", "SYSTEM"})
                String sender,
        @Schema(requiredMode = REQUIRED) String content,
        @Schema(requiredMode = REQUIRED) Instant sentAt) {

    public static MessageResponse from(MessageRecord m) {
        return new MessageResponse(m.id(), m.conversationId(), m.sender().name(), m.content(), m.sentAt());
    }

    /** The most recent messages of one conversation, oldest first. */
    @Schema(name = "Transcript")
    public record Transcript(
            @Schema(requiredMode = REQUIRED) UUID conversationId,
            @Schema(requiredMode = REQUIRED) List<MessageResponse> messages) {

        public static Transcript from(UUID conversationId, List<MessageRecord> messages) {
            return new Transcript(conversationId, messages.stream().map(MessageResponse::from).toList());
        }
    }
}
