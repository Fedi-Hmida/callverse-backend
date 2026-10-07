package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.domain.enums.Channel;
import java.time.Clock;
import java.util.Objects;

/**
 * Puts a new live contact in its skill's queue (QUEUED), scored by {@code PriorityCalculator} from
 * the customer's churn risk and segment and the contact's intent, then announces the arrival.
 *
 * <p>Serves {@code POST /api/v1/conversations} (staff). A customer opening a chat for themselves
 * needs "the customer whose login is me", which waits on schema change S-1.
 */
public class OpenConversationCommandHandler {

    private final ConversationLifecycle conversations;
    private final ConversationEvents events;
    private final Clock clock;

    public OpenConversationCommandHandler(ConversationLifecycle conversations, ConversationEvents events, Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public ConversationRecord handle(OpenConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.customerId() == null || command.skill() == null || command.skill().isBlank()) {
            throw new InvalidRequestException("customerId and skill are required");
        }
        ConversationRecord opened = conversations.open(
                command.customerId(), command.skill().strip(), command.intent(), Channel.CHAT, clock.instant());
        events.queued(opened);
        return opened;
    }
}
