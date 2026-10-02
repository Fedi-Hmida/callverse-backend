package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.Intent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The life of a live conversation: queued, taken, closed.
 *
 * <p><strong>Each write method is one transaction that locks before it decides.</strong> The rule
 * ({@code ConversationRules}) is applied to the state read under the row lock, so two callers racing
 * on one conversation are serialized and the second one sees the first one's result. Only live
 * conversations ({@code run_id IS NULL}) are touched; a simulation run never meets a live advisor.
 */
public interface ConversationLifecycle {

    /**
     * Puts a new live contact in its skill's queue, scored by {@code PriorityCalculator}.
     *
     * @throws com.callverse.core.application.exceptions.ResourceNotFoundException for an unknown
     *     customer or skill code
     */
    ConversationRecord open(UUID customerId, String skillCode, Intent intent, Channel channel, Instant now);

    /** A live conversation, or empty; a simulated one is not found. */
    Optional<ConversationRecord> find(UUID conversationId);

    /**
     * QUEUED → ASSIGNED for the highest-priority live conversation of {@code skillCode}.
     *
     * <p>Locks the advisor first, so one advisor taking twice at once cannot exceed
     * {@code max_concurrent}; then takes the head of the queue with {@code SKIP LOCKED}, so two
     * advisors taking at once get two different conversations and neither waits.
     *
     * @return the assigned conversation, or empty when nothing is waiting
     * @throws com.callverse.core.domain.exceptions.AdvisorUnavailableException at capacity
     * @throws com.callverse.core.application.exceptions.SlaPolicyNotFoundException when the skill has
     *     no active SLA policy and no global one exists
     */
    Optional<ConversationRecord> assignNext(UUID advisorId, String skillCode, Instant now);

    /**
     * Ends a conversation: {@code RESOLVED} or {@code ABANDONED}. Resolving an {@code ESCALATED}
     * conversation also resolves its pending escalation, recording who resolved it.
     *
     * @param expectedFrom the status the caller's permission was decided on; if the conversation
     *     moved since (an advisor's chat escalated a moment ago), the close is refused rather than
     *     carried out on a stale decision
     * @param closedByUserId the account that closed it; recorded on a resolved escalation
     */
    ConversationRecord close(
            UUID conversationId, ConversationStatus expectedFrom, ConversationStatus target, UUID closedByUserId,
            Instant now);

    /** What an advisor is holding: ASSIGNED, ACTIVE and ESCALATED, oldest assignment first. */
    List<ConversationRecord> heldBy(UUID advisorId);

    /**
     * A live conversation as the use cases see it. No priority score, wait, handle time or SLA flag:
     * those are internal operating data (ownership rule A7).
     *
     * @param customerUserId the login behind the customer, or null; who may read it as a customer
     * @param advisorUserId the login behind the assigned advisor, or null while QUEUED
     */
    record ConversationRecord(
            UUID id,
            UUID customerId,
            UUID customerUserId,
            UUID advisorId,
            UUID advisorUserId,
            String skill,
            Intent intent,
            Channel channel,
            ConversationStatus status,
            Instant queuedAt,
            Instant assignedAt,
            Instant endedAt) {}
}
