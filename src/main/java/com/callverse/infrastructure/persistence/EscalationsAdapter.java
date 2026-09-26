package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Escalation;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
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
                        .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));

        Optional<Escalation> pending =
                escalations.findFirstByConversationIdAndStatusOrderByCreatedAtAsc(
                        conversationId, EscalationStatus.PENDING);
        if (pending.isPresent()) {
            return new EscalationOutcome(toRecord(pending.get()), false);
        }

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
