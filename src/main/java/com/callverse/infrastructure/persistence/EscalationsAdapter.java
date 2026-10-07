package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Escalation;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import com.callverse.infrastructure.persistence.repositories.EscalationRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link Escalations} with Spring Data.
 *
 * <p><strong>How "at most one pending" is kept without a unique constraint.</strong>
 * {@link #raiseUnlessPending} locks the conversation's row first, then checks for a pending
 * escalation, then inserts — all in one transaction. A second concurrent call blocks on the lock
 * until the first commits, then finds its escalation and returns it. The schema cannot enforce this
 * itself (no unique index on {@code (conversation_id, status)}), so the lock is the guarantee.
 *
 * <p><strong>Raising an escalation moves the conversation to {@code ESCALATED}</strong> in the same
 * transaction, through the state machine: there is never an escalation on an ACTIVE conversation,
 * nor an ESCALATED conversation without its escalation.
 */
@Component
@RequiredArgsConstructor
class EscalationsAdapter implements Escalations {

    private final EscalationRepository escalations;
    private final ConversationRepository conversations;

    @Override
    @Transactional(readOnly = true)
    public Optional<EscalationRecord> findPending(UUID conversationId) {
        return escalations
                .findFirstByConversationIdAndStatusOrderByCreatedAtAsc(conversationId, EscalationStatus.PENDING)
                .map(EscalationsAdapter::toRecord);
    }

    @Override
    @Transactional
    public EscalationOutcome raiseUnlessPending(UUID conversationId, String reason, EscalationRaisedBy raisedBy) {
        Conversation conversation =
                conversations
                        .findByIdForUpdate(conversationId)
                        .filter(c -> c.getRunId() == null) // a simulation run is not escalated live
                        .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        Optional<Escalation> pending =
                escalations.findFirstByConversationIdAndStatusOrderByCreatedAtAsc(
                        conversationId, EscalationStatus.PENDING);
        if (pending.isPresent()) {
            if (conversation.getStatus() == ConversationStatus.ACTIVE) {
                // Raised before escalating moved the conversation: complete the move now.
                conversation.setStatus(ConversationStatus.ESCALATED);
            }
            return new EscalationOutcome(toRecord(pending.get()), false);
        }

        // Re-checked under the lock: the handler's check ran on a read that another call may have
        // overtaken. Raising the escalation and moving the conversation are one transaction.
        if (!conversation.getStatus().canTransitionTo(ConversationStatus.ESCALATED)) {
            throw new InvalidStateTransitionException(conversation.getStatus(), ConversationStatus.ESCALATED);
        }
        conversation.setStatus(ConversationStatus.ESCALATED);

        Escalation escalation = new Escalation();
        escalation.setConversation(conversation);
        escalation.setReason(reason);
        escalation.setRaisedBy(raisedBy);
        escalation.setStatus(EscalationStatus.PENDING);
        return new EscalationOutcome(toRecord(escalations.save(escalation)), true);
    }

    private static EscalationRecord toRecord(Escalation escalation) {
        return new EscalationRecord(
                escalation.getId(),
                escalation.getConversation().getId(),
                escalation.getReason(),
                escalation.getRaisedBy(),
                escalation.getStatus(),
                escalation.getCreatedAt());
    }
}
