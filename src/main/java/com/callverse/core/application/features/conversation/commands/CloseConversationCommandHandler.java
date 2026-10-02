package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.ConversationAccessPolicy;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.domain.enums.ConversationStatus;
import java.time.Clock;
import java.util.Objects;

/**
 * Resolves or abandons a conversation.
 *
 * <p>Serves {@code POST /api/v1/conversations/{id}/resolve} and {@code .../abandon}.
 *
 * <ul>
 *   <li><strong>Resolve:</strong> the assigned advisor, from ACTIVE; a supervisor, from ESCALATED
 *       (which also resolves the pending escalation).
 *   <li><strong>Abandon:</strong> the customer who left, the assigned advisor, or operations staff,
 *       from QUEUED, ASSIGNED or ACTIVE. An escalated conversation is never abandoned.
 * </ul>
 *
 * <p>Who may act is decided on the status read here, and the adapter refuses the close if the
 * status moved in between — so an advisor cannot resolve a conversation escalated a moment ago.
 * Handle time (or, for a customer who left the queue, wait time) is measured at this transition.
 */
public class CloseConversationCommandHandler {

    private final ConversationLifecycle conversations;
    private final ConversationEvents events;
    private final Clock clock;

    public CloseConversationCommandHandler(ConversationLifecycle conversations, ConversationEvents events, Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public ConversationRecord handle(CloseConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ConversationStatus target = command.target();
        if (target != ConversationStatus.RESOLVED && target != ConversationStatus.ABANDONED) {
            throw new InvalidRequestException("A conversation is closed as RESOLVED or ABANDONED");
        }
        ConversationRecord conversation = conversations.find(command.conversationId())
                .filter(c -> ConversationAccessPolicy.canRead(command.caller(), c))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", command.conversationId()));
        boolean allowed = target == ConversationStatus.RESOLVED
                ? ConversationAccessPolicy.canResolve(command.caller(), conversation)
                : ConversationAccessPolicy.canAbandon(command.caller(), conversation);
        if (!allowed) {
            throw new ActionNotPermittedException(
                    target == ConversationStatus.RESOLVED && conversation.status() == ConversationStatus.ESCALATED
                            ? "An escalated conversation is resolved by a supervisor."
                            : "You may not close this conversation.");
        }

        ConversationRecord closed = conversations.close(
                conversation.id(), conversation.status(), target, command.caller().userId(), clock.instant());
        if (conversation.status() == ConversationStatus.QUEUED) {
            events.leftQueue(closed);
        } else {
            events.statusChanged(closed);
        }
        return closed;
    }
}
