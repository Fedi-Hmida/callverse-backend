package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.core.application.interfaces.ConversationDirectory.ConversationRef;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.application.interfaces.Escalations.EscalationOutcome;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import java.util.Objects;
import java.util.Optional;

/**
 * Raises an escalation on a conversation — formerly the agent tool
 * {@code POST /internal/conversations/{id}/escalate} ({@code OWNERSHIP_RULES.md} E7).
 *
 * <p>Serves {@code POST /api/v1/conversations/{id}/escalations} (advisors). The AI tool that once
 * called it was withdrawn on 2026-09-30.
 *
 * <p><strong>Three rules, in this order.</strong>
 *
 * <ol>
 *   <li><em>Idempotent.</em> If a pending escalation already exists it is returned, not duplicated:
 *       an agent that times out and retries must not queue several escalations for one supervisor.
 *       Checked first, so a retry is answered the same way whatever state the conversation is in.
 *   <li><em>Only where the state machine allows it.</em> {@link ConversationStatus#canTransitionTo}
 *       permits {@code ESCALATED} from {@code ACTIVE} alone; anything else is 409
 *       {@code INVALID_STATE_TRANSITION}.
 *   <li><em>Raised by whoever the route says.</em> The route, not the request body, decides
 *       {@code raised_by}: the advisor route always passes {@code ADVISOR}, so a caller cannot
 *       claim to be the AI or a rule.
 * </ol>
 *
 * <p><strong>What it does not do: move the conversation to {@code ESCALATED}.</strong> Transitions
 * belong to the conversation state machine of Phase 4.5, which also owns the supervisor queue and
 * its notifications. Until then this records the escalation and leaves the status as it was — so a
 * conversation can be {@code ACTIVE} with a pending escalation. Phase 4.5 must perform the
 * transition here, not beside it.
 */
public class EscalateConversationCommandHandler {

    private final ConversationDirectory conversations;
    private final Escalations escalations;

    public EscalateConversationCommandHandler(ConversationDirectory conversations, Escalations escalations) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.escalations = Objects.requireNonNull(escalations, "escalations must not be null");
    }

    public EscalationOutcome handle(EscalateConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ConversationRef conversation =
                conversations
                        .find(command.conversationId())
                        .orElseThrow(() -> new ResourceNotFoundException("Conversation", command.conversationId()));

        Optional<Escalations.EscalationRecord> pending = escalations.findPending(conversation.id());
        if (pending.isPresent()) {
            return new EscalationOutcome(pending.get(), false);
        }
        if (!conversation.status().canTransitionTo(ConversationStatus.ESCALATED)) {
            throw new InvalidStateTransitionException(conversation.status(), ConversationStatus.ESCALATED);
        }
        return escalations.raiseUnlessPending(
                conversation.id(),
                command.reason().trim(),
                Objects.requireNonNull(command.raisedBy(), "raisedBy must not be null"));
    }
}
