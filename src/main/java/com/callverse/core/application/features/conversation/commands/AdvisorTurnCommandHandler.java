package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.AdvisorAgent;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorRequest;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorResponse;
import com.callverse.core.application.interfaces.AdvisorAgent.CustomerContext;
import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.application.interfaces.AdvisorAgent.Source;
import com.callverse.core.application.interfaces.AdvisorAgent.ToolCall;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.ConversationMessages.PostedMessage;
import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Obtains and stores one Customer Advisor turn after the triggering customer message has committed.
 *
 * <p>No database lock is held while the outbound AI call runs. The final write rechecks the state
 * and triggering message under the conversation row lock, discarding a stale response.
 */
public class AdvisorTurnCommandHandler {

    private static final Logger LOG = System.getLogger(AdvisorTurnCommandHandler.class.getName());
    private static final int TRANSCRIPT_LIMIT = 200;
    private static final int HISTORY_LIMIT = 20;

    private final AdvisorAgent agent;
    private final ConversationLifecycle conversations;
    private final ConversationMessages messages;
    private final CustomerRecords customers;
    private final ConversationEvents events;
    private final Clock clock;

    public AdvisorTurnCommandHandler(
            AdvisorAgent agent,
            ConversationLifecycle conversations,
            ConversationMessages messages,
            CustomerRecords customers,
            ConversationEvents events,
            Clock clock) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void handle(AdvisorTurnCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ConversationRecord conversation = conversations.find(command.conversationId()).orElse(null);
        if (conversation == null) {
            LOG.log(Level.WARNING, "AI Advisor skipped: conversation no longer exists");
            return;
        }
        if (conversation.status() != ConversationStatus.ASSIGNED
                && conversation.status() != ConversationStatus.ACTIVE) {
            LOG.log(Level.INFO, "AI Advisor skipped for conversation {0} in state {1}",
                    conversation.id(), conversation.status());
            return;
        }

        List<MessageRecord> transcript = messages.recent(conversation.id(), TRANSCRIPT_LIMIT);
        if (transcript.isEmpty()
                || transcript.getLast().id() != command.triggeringCustomerMessageId()
                || transcript.getLast().sender() != MessageSender.CUSTOMER) {
            LOG.log(Level.INFO, "AI Advisor skipped for conversation {0}: a newer message exists", conversation.id());
            return;
        }
        MessageRecord customerMessage = transcript.getLast();

        CustomerProfile customer = customers.findProfile(conversation.customerId()).orElse(null);
        if (customer == null) {
            LOG.log(Level.WARNING, "AI Advisor skipped: customer context is unavailable");
            return;
        }

        List<MessageRecord> previousMessages = transcript.subList(0, transcript.size() - 1).stream()
                .filter(message -> message.sender() != MessageSender.SYSTEM)
                .toList();
        int historyStart = Math.max(0, previousMessages.size() - HISTORY_LIMIT);
        List<HistoryMessage> history = previousMessages.subList(historyStart, previousMessages.size()).stream()
                .map(message -> new HistoryMessage(message.sender(), message.content(), message.sentAt()))
                .toList();

        AdvisorResponse response;
        try {
            response = agent.respond(new AdvisorRequest(
                    conversation.id(),
                    conversation.customerId(),
                    customerMessage.content(),
                    history,
                    new CustomerContext(customer.tenureMonths(), customer.segment())));
            validate(response);
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "AI Advisor failed for conversation {0}; leaving it for a human",
                    conversation.id());
            return;
        }

        messages.postAgentTurn(
                        conversation.id(),
                        customerMessage.id(),
                        response.reply(),
                        response.sources(),
                        response.toolCalls(),
                        clock.instant())
                .ifPresentOrElse(events::messagePosted, () -> LOG.log(
                        Level.INFO, "AI Advisor response discarded for conversation {0}: it moved on",
                        conversation.id()));
    }

    private static void validate(AdvisorResponse response) {
        if (response == null
                || response.reply() == null
                || response.reply().isBlank()
                || response.reply().length() > 2000
                || response.intent() == null
                || response.intent().isBlank()
                || !Double.isFinite(response.confidence())
                || response.confidence() < 0
                || response.confidence() > 1
                || response.actionType() == null
                || response.actionType().isBlank()
                || response.latencyMs() < 0
                || response.sources().stream().anyMatch(source -> !valid(source))
                || response.toolCalls().stream().anyMatch(toolCall -> !valid(toolCall))) {
            throw new IllegalArgumentException("AI Advisor response does not match its contract");
        }
    }

    private static boolean valid(Source source) {
        return source != null && Double.isFinite(source.score()) && source.score() >= 0 && source.score() <= 1;
    }

    private static boolean valid(ToolCall toolCall) {
        return toolCall != null
                && toolCall.tool() != null
                && !toolCall.tool().isBlank()
                && toolCall.args() != null
                && toolCall.resultSummary() != null;
    }
}
