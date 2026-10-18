package com.callverse.core.application.features.customer;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.ConversationMessages.PostedMessage;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.CustomerConversationRemoval;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.UserRole;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer removes a conversation from their own space ({@code DELETE /api/v1/me/conversations/{id}}).
 *
 * <p>The bank keeps the record: a live conversation ends as ABANDONED and a SYSTEM notice joins the
 * transcript, so the advisor and the supervisor see what happened, live and after a reload. Three
 * announcements, in this order: the notice on the conversation topic, the status change (and the
 * queue departure for a waiting contact), then the removal on the skill queue, which reaches every
 * advisor of that skill even when another conversation is on their screen.
 */
public class RemoveOwnConversationCommandHandler {

    static final String NOTICE = "Le client a supprimé cette conversation de son espace.";

    private final CurrentPrincipalProvider principals;
    private final CustomerConversationRemoval removal;
    private final ConversationEvents events;
    private final Clock clock;

    public RemoveOwnConversationCommandHandler(CurrentPrincipalProvider principals, CustomerConversationRemoval removal,
                                               ConversationEvents events, Clock clock) {
        this.principals = Objects.requireNonNull(principals, "principals must not be null");
        this.removal = Objects.requireNonNull(removal, "removal must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void handle(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        var caller = principals.current().orElseThrow(AuthenticationRequiredException::new);
        if (caller.role() != UserRole.CUSTOMER) {
            throw new ActionNotPermittedException("Customer access required.");
        }
        var removed = removal.remove(conversationId, caller.userId(), NOTICE, clock.instant());

        events.messagePosted(new PostedMessage(removed.notice(), removed.conversation(), false));
        if (removed.statusBefore() == ConversationStatus.QUEUED) {
            events.leftQueue(removed.conversation());
        } else if (removed.statusBefore() != removed.conversation().status()) {
            events.statusChanged(removed.conversation());
        }
        events.removedByCustomer(removed.conversation());
    }
}
