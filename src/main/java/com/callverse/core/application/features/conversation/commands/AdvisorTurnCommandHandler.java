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
import com.callverse.core.domain.enums.AiAdvisorMode;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.services.PersonalDataRedactor;
import com.callverse.core.domain.services.AdvisorSafetyPolicy;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Obtains and stores one Customer Advisor turn after the triggering customer message has committed.
 *
 * <p>No database lock is held while the outbound AI call runs. The final write rechecks the state
 * and triggering message under the conversation row lock, discarding a stale response.
 *
 * <p>In {@link AiAdvisorMode#AUTONOMOUS}, every open conversation receives AI replies, including
 * waiting and escalated contacts, until a human advisor replies. Human takeover, a newer
 * message or closure invalidates a response under the conversation lock.
 */
public class AdvisorTurnCommandHandler {

    private static final Logger LOG = System.getLogger(AdvisorTurnCommandHandler.class.getName());
    private static final int TRANSCRIPT_LIMIT = 200;
    private static final int HISTORY_LIMIT = 20;
    private static final String ESCALATE = "ESCALATE";
    private static final String DEFAULT_ESCALATION_REASON = "Escalade demandée par l'assistant IA";
    /** The escalation reason column and request limit. */
    private static final int MAX_ESCALATION_REASON = 255;

    private final AdvisorAgent agent;
    private final ConversationLifecycle conversations;
    private final ConversationMessages messages;
    private final CustomerRecords customers;
    private final ConversationEvents events;
    private final Clock clock;
    private final AiAdvisorMode mode;
    private final EscalateConversationCommandHandler escalation;

    public AdvisorTurnCommandHandler(
            AdvisorAgent agent,
            ConversationLifecycle conversations,
            ConversationMessages messages,
            CustomerRecords customers,
            ConversationEvents events,
            Clock clock,
            AiAdvisorMode mode,
            EscalateConversationCommandHandler escalation) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.escalation = Objects.requireNonNull(escalation, "escalation must not be null");
    }

    /** False in {@code OFF} mode, so callers can skip scheduling a turn at all. */
    public boolean enabled() {
        return mode != AiAdvisorMode.OFF;
    }

    public void handle(AdvisorTurnCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (!enabled()) {
            return;
        }
        ConversationRecord conversation = conversations.find(command.conversationId()).orElse(null);
        if (conversation == null) {
            LOG.log(Level.WARNING, "AI Advisor skipped: conversation no longer exists");
            return;
        }
        if (conversation.status().isTerminal()) {
            LOG.log(Level.INFO, "AI Advisor skipped for conversation {0} in state {1}",
                    conversation.id(), conversation.status());
            return;
        }
        if (messages.humanAdvisorHasReplied(conversation.id())) {
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
                .map(message -> new HistoryMessage(
                        message.sender(), PersonalDataRedactor.redact(message.content()), message.sentAt()))
                .toList();

        // Text crosses to an external model: identifiers are masked here, the database keeps them.
        AdvisorResponse response;
        try {
            response = AdvisorSafetyPolicy.reportsFraud(customerMessage.content())
                    ? fraudHandover() : AdvisorSafetyPolicy.requestsHuman(customerMessage.content())
                    ? humanHandover() : agent.respond(new AdvisorRequest(
                    conversation.id(),
                    conversation.customerId(),
                    PersonalDataRedactor.redact(customerMessage.content()),
                    history,
                    new CustomerContext(customer.tenureMonths(), customer.segment())));
            validate(response);
            if ("FRAUD".equals(response.intent())) {
                AdvisorResponse safe = fraudHandover();
                response = new AdvisorResponse(safe.reply(), safe.intent(), safe.confidence(),
                        response.toolCalls(), response.sources(), safe.actionType(),
                        safe.actionPayload(), response.latencyMs());
            } else if (AdvisorSafetyPolicy.claimsCardBlock(response.reply())) {
                response = new AdvisorResponse(
                        "Je ne peux pas confirmer le blocage de votre carte. Une reprise par un superviseur est nécessaire.",
                        response.intent(), 0, response.toolCalls(), response.sources(), ESCALATE,
                        Map.of("reason", "Blocage de carte non exécuté par l'assistant"), response.latencyMs());
            }
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "AI Advisor failed for conversation {0}; leaving it for a human",
                    conversation.id());
            response = new AdvisorResponse(
                    "L'assistant est temporairement indisponible. Votre demande nécessite une reprise par un superviseur.",
                    "OTHER", 0, List.of(), List.of(), ESCALATE,
                    Map.of("reason", "Assistant IA indisponible : reprise humaine nécessaire"), 0);
        }

        response = new AdvisorResponse(AdvisorSafetyPolicy.customerReply(response.reply()), response.intent(),
                response.confidence(), response.toolCalls(), response.sources(), response.actionType(),
                response.actionPayload(), response.latencyMs());
        Optional<PostedMessage> posted = messages.postExplainedAgentTurn(
                conversation.id(),
                customerMessage.id(),
                response,
                clock.instant());
        if (posted.isEmpty()) {
            LOG.log(Level.INFO, "AI Advisor response discarded for conversation {0}: it moved on",
                    conversation.id());
            return;
        }
        events.messagePosted(posted.get());
        if (ESCALATE.equals(response.actionType())) {
            escalate(conversation.id(), response.actionPayload(), posted.get().conversation().status() == ConversationStatus.QUEUED);
        }
    }

    private static AdvisorResponse fraudHandover() {
        return new AdvisorResponse(
                "Votre signalement nécessite une prise en charge par un superviseur. "
                        + "Aucun blocage de carte ni remboursement n'a été effectué par l'assistant.",
                "FRAUD", 1, List.of(), List.of(), ESCALATE,
                Map.of("reason", "Suspicion de fraude : prise en charge humaine obligatoire"), 0);
    }

    private static AdvisorResponse humanHandover() {
        return new AdvisorResponse(
                "Votre demande de conseiller humain est transmise. Je reste disponible pendant votre attente, jusqu’à la réponse d’un membre de l’équipe.",
                "OTHER", 1, List.of(), List.of(), ESCALATE,
                Map.of("reason", "Reprise humaine demandée par le client"), 0);
    }

    /**
     * The reply told the customer a supervisor takes over, so a real escalation must follow. It
     * runs after the reply is posted. Queued contacts may go directly to supervision; assigned
     * contacts first become ACTIVE. An existing escalation is returned without duplication.
     */
    private void escalate(UUID conversationId, Map<String, Object> payload, boolean wasQueued) {
        Object given = payload == null ? null : payload.get("reason");
        String reason = given instanceof String text && !text.isBlank() ? text.strip() : DEFAULT_ESCALATION_REASON;
        if (reason.length() > MAX_ESCALATION_REASON) {
            reason = reason.substring(0, MAX_ESCALATION_REASON);
        }
        try {
            escalation.handle(new EscalateConversationCommand(conversationId, reason, EscalationRaisedBy.AI, null));
            if (wasQueued) {
                conversations.find(conversationId)
                        .filter(record -> record.status() == ConversationStatus.ESCALATED)
                        .ifPresent(events::leftQueue);
            }
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "AI Advisor escalation refused for conversation {0}: {1}",
                    conversationId, exception.getClass().getSimpleName());
        }
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
