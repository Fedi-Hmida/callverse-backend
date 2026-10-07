package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.ConversationDirectory.ConversationRef;
import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.core.application.interfaces.Escalations.EscalationOutcome;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.SupervisionAlert;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import java.util.Objects;
import java.util.Optional;

/**
 * Raises an escalation on a conversation and hands it to the supervisors.
 *
 * <p>Serves {@code POST /api/v1/conversations/{id}/escalations} (advisors).
 *
 * <p><strong>Four rules, in this order.</strong>
 *
 * <ol>
 *   <li><em>Only the conversation's own advisor</em> escalates it (ownership rule B5). Anyone else is
 *       answered 404, as if the conversation did not exist: its existence is not theirs to learn.
 *   <li><em>Idempotent.</em> If a pending escalation already exists it is returned, not duplicated:
 *       a retry must not queue several escalations for one supervisor.
 *   <li><em>Only where the state machine allows it.</em> {@link ConversationStatus#canTransitionTo}
 *       permits {@code ESCALATED} from {@code ACTIVE} alone; anything else is 409
 *       {@code INVALID_STATE_TRANSITION}. The adapter re-checks under the row lock.
 *   <li><em>Raised by whoever the route says.</em> The route, not the request body, decides
 *       {@code raised_by}.
 * </ol>
 *
 * <p><strong>The conversation moves to {@code ESCALATED}</strong> in the same transaction as the
 * escalation row, and from then on only a supervisor resolves it.
 *
 * <p><strong>Supervisors are told.</strong> A newly created escalation raises an
 * {@code ESCALATION_RAISED} supervision alert and a status change on the conversation's topic;
 * returning an escalation that was already pending raises nothing.
 */
public class EscalateConversationCommandHandler {

    private final ConversationDirectory conversations;
    private final Escalations escalations;
    private final RealtimeEventPublisher events;
    private final ConversationEvents conversationEvents;

    public EscalateConversationCommandHandler(
            ConversationDirectory conversations,
            Escalations escalations,
            RealtimeEventPublisher events,
            ConversationEvents conversationEvents) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.escalations = Objects.requireNonNull(escalations, "escalations must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.conversationEvents = Objects.requireNonNull(conversationEvents, "conversationEvents must not be null");
    }

    public EscalationOutcome handle(EscalateConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ConversationRef conversation =
                conversations
                        .find(command.conversationId())
                        .orElseThrow(() -> new ResourceNotFoundException("Conversation", command.conversationId()));
        if (command.raisedBy() == EscalationRaisedBy.ADVISOR
                && (conversation.advisorUserId() == null
                        || !conversation.advisorUserId().equals(command.requestedByUserId()))) {
            throw new ResourceNotFoundException("Conversation", command.conversationId());
        }

        Optional<Escalations.EscalationRecord> pending = escalations.findPending(conversation.id());
        if (pending.isPresent()) {
            if (conversation.status() == ConversationStatus.ACTIVE) {
                // Raised before escalating moved the conversation: the retry completes the move.
                EscalationOutcome healed = escalations.raiseUnlessPending(
                        conversation.id(), command.reason().trim(), command.raisedBy());
                conversationEvents.statusChanged(conversation.id(), ConversationStatus.ESCALATED);
                return healed;
            }
            return new EscalationOutcome(pending.get(), false);
        }
        if (!conversation.status().canTransitionTo(ConversationStatus.ESCALATED)) {
            throw new InvalidStateTransitionException(conversation.status(), ConversationStatus.ESCALATED);
        }
        EscalationOutcome outcome = escalations.raiseUnlessPending(
                conversation.id(),
                command.reason().trim(),
                Objects.requireNonNull(command.raisedBy(), "raisedBy must not be null"));
        if (outcome.created()) {
            // Only a new escalation is news; a concurrent duplicate resolved under the lock is not.
            events.publishSupervisionAlert(SupervisionAlert.escalationRaised(
                    outcome.escalation().id(), conversation.id(), conversation.customerId(),
                    outcome.escalation().createdAt()));
            conversationEvents.statusChanged(conversation.id(), ConversationStatus.ESCALATED);
        }
        return outcome;
    }
}
