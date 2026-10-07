package com.callverse.core.domain.services;

import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.exceptions.AdvisorUnavailableException;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * What each step of a conversation's life is allowed to do, and what it measures.
 *
 * <p><strong>The edges come from {@link ConversationStatus#canTransitionTo}, never from here.</strong>
 * This class adds what the enum cannot know: the measurements written at the moment of a transition
 * ({@code wait_seconds}, {@code handle_seconds}, {@code sla_met}, written once and never recomputed
 * on read) and who may write in which state.
 *
 * <p>Pure functions over values, so the adapters call them while they hold the row lock and the
 * rule is checked against the state that is actually being changed.
 *
 * <p><strong>Seconds are whole and truncated.</strong> 30.9 s against a 30 s target is 30 s and
 * meets it: the columns are integers, and truncation is what a stopwatch shows.
 */
public final class ConversationRules {

    private ConversationRules() {
        // Utility holder for pure functions; never instantiated.
    }

    /** The measurements taken when an advisor takes a conversation from the queue. */
    public record Assignment(int waitSeconds, boolean slaMet) {}

    /**
     * The measurements taken when a conversation ends. {@code waitSeconds} is set only when the
     * customer left the queue without being served; {@code handleSeconds} only when someone served it.
     */
    public record Closure(Integer waitSeconds, Integer handleSeconds) {}

    /**
     * QUEUED → ASSIGNED.
     *
     * @param slaTargetSeconds the skill's SLA target; met when the wait is at most this long
     */
    public static Assignment assign(ConversationStatus from, Instant queuedAt, Instant now, int slaTargetSeconds) {
        require(from, ConversationStatus.ASSIGNED);
        int wait = seconds(queuedAt, now);
        return new Assignment(wait, wait <= slaTargetSeconds);
    }

    /**
     * The status after an advisor writes: their first message engages an ASSIGNED conversation
     * (ASSIGNED → ACTIVE); an ACTIVE or ESCALATED one stays as it is. A queued or closed one refuses.
     */
    public static ConversationStatus statusAfterAdvisorMessage(ConversationStatus from) {
        return switch (from) {
            case ASSIGNED -> ConversationStatus.ACTIVE;
            case ACTIVE, ESCALATED -> from;
            case QUEUED, RESOLVED, ABANDONED -> throw InvalidStateTransitionException.noMessagesIn(from, "advisor");
        };
    }

    /** A customer may write while waiting or being served (ownership rule A10), never once closed. */
    public static boolean acceptsCustomerMessage(ConversationStatus status) {
        return !status.isTerminal();
    }

    /**
     * → RESOLVED or ABANDONED.
     *
     * @param assignedAt null if no advisor ever took it
     */
    public static Closure close(
            ConversationStatus from, ConversationStatus to, Instant queuedAt, Instant assignedAt, Instant now) {
        if (to == null || !to.isTerminal()) {
            throw new IllegalArgumentException("close ends a conversation; " + to + " is not an end state");
        }
        require(from, to);
        if (assignedAt == null) {
            return new Closure(seconds(queuedAt, now), null);
        }
        return new Closure(null, seconds(assignedAt, now));
    }

    /**
     * The SLA outcome of a customer who left the queue unanswered. Giving up after the target is a
     * miss: a contact centre that let people wait until they hung up did not meet its target, and
     * leaving these out would flatter the ratio the more customers gave up. Giving up within the
     * target is neither a hit nor a miss (null) — the usual "short abandon" convention: nobody could
     * have answered it in time anyway.
     */
    public static Boolean slaMetWhenLeavingQueue(int waitSeconds, int slaTargetSeconds) {
        return waitSeconds > slaTargetSeconds ? Boolean.FALSE : null;
    }

    /**
     * An advisor already holding {@code held} conversations may take another only below
     * {@code maxConcurrent}.
     */
    public static void requireCapacity(UUID advisorId, long held, int maxConcurrent) {
        if (held >= maxConcurrent) {
            throw AdvisorUnavailableException.atCapacity(advisorId, held, maxConcurrent);
        }
    }

    private static void require(ConversationStatus from, ConversationStatus to) {
        if (from == null || !from.canTransitionTo(to)) {
            throw new InvalidStateTransitionException(from, to);
        }
    }

    /** Whole seconds from {@code start} to {@code end}, never negative if the clock moved backwards. */
    private static int seconds(Instant start, Instant end) {
        long seconds = Duration.between(start, end).getSeconds();
        return (int) Math.max(0, Math.min(seconds, Integer.MAX_VALUE));
    }
}
