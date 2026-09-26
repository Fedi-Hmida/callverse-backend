package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and raises escalations.
 *
 * <p><strong>"At most one pending escalation per conversation" is part of this contract</strong>,
 * not a caller's responsibility — the same way {@code AppUserDirectory} puts "active" into its method
 * name. The schema has no unique constraint on {@code (conversation_id, status)}, so a check in the
 * handler followed by an insert in a separate transaction would let two concurrent retries both pass
 * the check. {@link #raiseUnlessPending} performs the check and the insert atomically.
 */
public interface Escalations {

    Optional<EscalationRecord> findPending(UUID conversationId);

    /**
     * Raises an escalation unless the conversation already has a pending one, atomically.
     *
     * @return the new escalation with {@code created = true}, or the existing pending one with
     *     {@code created = false}
     */
    EscalationOutcome raiseUnlessPending(UUID conversationId, String reason, EscalationRaisedBy raisedBy);

    record EscalationRecord(
            UUID id,
            UUID conversationId,
            String reason,
            EscalationRaisedBy raisedBy,
            EscalationStatus status,
            Instant createdAt) {}

    record EscalationOutcome(EscalationRecord escalation, boolean created) {}
}
