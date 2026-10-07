package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.features.conversation.ConversationAccessPolicy;
import com.callverse.core.application.interfaces.AdvisorDirectory;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import java.util.Objects;
import java.util.UUID;

/**
 * Who may listen to a conversation topic or a queue topic, checked when the client subscribes.
 *
 * <p>The same rules as the REST reads, through the same {@link ConversationAccessPolicy}: a
 * subscription is a read that keeps going, and a check made only on the HTTP route would leave the
 * live topic as a second, open door (rules A15 and B14).
 */
public class ConversationSubscriptionPolicy {

    private final ConversationLifecycle conversations;
    private final AdvisorDirectory advisors;

    public ConversationSubscriptionPolicy(ConversationLifecycle conversations, AdvisorDirectory advisors) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.advisors = Objects.requireNonNull(advisors, "advisors must not be null");
    }

    /** @param conversationId the topic's last segment, as the client sent it */
    public boolean mayListenToConversation(AuthenticatedPrincipal caller, String conversationId) {
        UUID id;
        try {
            id = UUID.fromString(conversationId);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
        if (!id.toString().equals(conversationId)) {
            // UUID.fromString is lenient ("1-1-1-1-1", upper case); events go to the canonical form
            // only, so any other spelling would be a subscription that never hears anything.
            return false;
        }
        return conversations.find(id).filter(c -> ConversationAccessPolicy.canRead(caller, c)).isPresent();
    }

    /** Supervisors and administrators listen to any queue; an advisor only to a skill they hold. */
    public boolean mayListenToQueue(AuthenticatedPrincipal caller, String skill) {
        return switch (caller.role()) {
            case SUPERVISOR, ADMIN -> true;
            case ADVISOR -> advisors.findByUserId(caller.userId()).map(a -> a.holds(skill)).orElse(false);
            case CUSTOMER -> false;
        };
    }
}
