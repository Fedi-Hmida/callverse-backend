package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.exceptions.AdvisorProfileNotFoundException;
import com.callverse.core.application.interfaces.AdvisorDirectory;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import java.util.List;
import java.util.Objects;

/**
 * What the calling advisor holds now: ASSIGNED, ACTIVE and ESCALATED, oldest first (rule B5). The
 * header of the advisor's workstation.
 */
public class GetHeldConversationsQueryHandler {

    private final AdvisorDirectory advisors;
    private final ConversationLifecycle conversations;

    public GetHeldConversationsQueryHandler(AdvisorDirectory advisors, ConversationLifecycle conversations) {
        this.advisors = Objects.requireNonNull(advisors, "advisors must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
    }

    public List<ConversationRecord> handle(GetHeldConversationsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return advisors.findByUserId(query.caller().userId())
                .map(advisor -> conversations.heldBy(advisor.id()))
                .orElseThrow(() -> new AdvisorProfileNotFoundException(query.caller().userId()));
    }
}
