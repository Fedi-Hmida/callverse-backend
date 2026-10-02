package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.ConversationAccessPolicy;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import java.util.Objects;

/**
 * One live conversation, for its customer, its advisor and supervisors. Anyone else is answered
 * 404, exactly like a conversation that does not exist (rules A6, B5).
 */
public class GetConversationQueryHandler {

    private final ConversationLifecycle conversations;

    public GetConversationQueryHandler(ConversationLifecycle conversations) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
    }

    public ConversationRecord handle(GetConversationQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return conversations.find(query.conversationId())
                .filter(c -> ConversationAccessPolicy.canRead(query.caller(), c))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", query.conversationId()));
    }
}
