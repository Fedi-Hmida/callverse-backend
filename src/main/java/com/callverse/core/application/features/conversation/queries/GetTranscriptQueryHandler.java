package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.ConversationAccessPolicy;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import java.util.List;
import java.util.Objects;

/**
 * The most recent messages of a conversation, oldest first, for the people who may read it (rules
 * A8, B8). Bounded: a screen opens on the latest turns and the live topic brings the rest.
 */
public class GetTranscriptQueryHandler {

    public static final int MAX_LIMIT = 200;

    private final ConversationLifecycle conversations;
    private final ConversationMessages messages;

    public GetTranscriptQueryHandler(ConversationLifecycle conversations, ConversationMessages messages) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public List<MessageRecord> handle(GetTranscriptQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.limit() < 1 || query.limit() > MAX_LIMIT) {
            throw new InvalidRequestException("limit must be between 1 and %d".formatted(MAX_LIMIT));
        }
        conversations.find(query.conversationId())
                .filter(c -> ConversationAccessPolicy.canRead(query.caller(), c))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", query.conversationId()));
        return messages.recent(query.conversationId(), query.limit());
    }
}
