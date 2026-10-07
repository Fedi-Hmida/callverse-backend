package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.ConversationAccessPolicy;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.ConversationMessages.PostedMessage;
import com.callverse.core.domain.enums.MessageSender;
import java.time.Clock;
import java.util.Objects;

/**
 * Writes a message into a conversation and pushes it to everyone allowed to read it.
 *
 * <p>Serves {@code POST /api/v1/conversations/{id}/messages}. Only the two parties write: the
 * assigned advisor as ADVISOR, the owning customer as CUSTOMER (rule A10: the sender is never read
 * from the request). Anyone who may not read the conversation is answered 404; a supervisor, who
 * may read it, is answered 403. The advisor's first message engages the conversation
 * (ASSIGNED → ACTIVE). Closed conversations accept nothing (409 {@code INVALID_STATE_TRANSITION}).
 *
 * <p>An advisor keeps writing after escalating: the customer is still on the line while the
 * supervisor takes over the decision, and only the supervisor can end it.
 */
public class PostMessageCommandHandler {

    /** A chat turn, not a document. */
    public static final int MAX_LENGTH = 2000;

    private final ConversationLifecycle conversations;
    private final ConversationMessages messages;
    private final ConversationEvents events;
    private final Clock clock;

    public PostMessageCommandHandler(
            ConversationLifecycle conversations, ConversationMessages messages, ConversationEvents events, Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public MessageRecord handle(PostMessageCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String content = command.content() == null ? "" : command.content().strip();
        if (content.isEmpty() || content.length() > MAX_LENGTH) {
            throw new InvalidRequestException("content must be 1 to %d characters".formatted(MAX_LENGTH));
        }
        ConversationRecord conversation = conversations.find(command.conversationId())
                .filter(c -> ConversationAccessPolicy.canRead(command.caller(), c))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", command.conversationId()));
        MessageSender sender = ConversationAccessPolicy.senderFor(command.caller(), conversation)
                .orElseThrow(() -> new ActionNotPermittedException(
                        "Only the customer and the assigned advisor write in a conversation."));

        PostedMessage posted = messages.post(conversation.id(), sender, content, clock.instant());
        events.messagePosted(posted);
        return posted.message();
    }
}
