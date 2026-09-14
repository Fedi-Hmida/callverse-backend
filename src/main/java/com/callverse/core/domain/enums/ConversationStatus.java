package com.callverse.core.domain.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle state of a conversation, and the only place the legal transitions between those states
 * are written down.
 *
 * <p>The state machine, reproduced from the schema reference:
 *
 * <pre>
 * QUEUED ──&gt; ASSIGNED ──&gt; ACTIVE ──&gt; RESOLVED
 *    │           │           │
 *    │           │           └──&gt; ESCALATED ──&gt; RESOLVED
 *    └───────────┴──────────────&gt; ABANDONED
 * </pre>
 *
 * <p>The database {@code CHECK} constraint guarantees only that the column holds one of these six
 * values. It cannot express that {@code RESOLVED → QUEUED} is nonsense, so that rule lives here,
 * with the type, rather than being reinvented in the router, the SLA sweep and the simulation
 * runner independently and inconsistently.
 *
 * <p>No use case consumes this yet — enforcement arrives with the conversation feature slice, which
 * will reject an illegal transition with {@code INVALID_STATE_TRANSITION}. It is defined now so
 * that when three callers need it, they find it instead of writing it.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the column's
 * SQL CHECK constraint character for character.
 */
public enum ConversationStatus {

    /** Waiting in the queue for an advisor. The entry state; nothing precedes it. */
    QUEUED,

    /** An advisor has been chosen but has not yet engaged. */
    ASSIGNED,

    /** The advisor and the customer are exchanging messages. */
    ACTIVE,

    /** Handed to a supervisor. Can only be resolved from here, never abandoned. */
    ESCALATED,

    /** Terminal: the customer's need was met. */
    RESOLVED,

    /** Terminal: the customer left before resolution. Counts against the abandonment KPI. */
    ABANDONED;

    private static final Map<ConversationStatus, Set<ConversationStatus>> TRANSITIONS;

    static {
        // Built in a static initialiser rather than in the constructor: an enum constant cannot
        // reference its siblings while they are still being constructed.
        Map<ConversationStatus, Set<ConversationStatus>> transitions =
                new EnumMap<>(ConversationStatus.class);
        transitions.put(QUEUED, EnumSet.of(ASSIGNED, ABANDONED));
        transitions.put(ASSIGNED, EnumSet.of(ACTIVE, ABANDONED));
        transitions.put(ACTIVE, EnumSet.of(RESOLVED, ESCALATED, ABANDONED));
        // Deliberately cannot be abandoned: once a supervisor owns it, it is seen through.
        transitions.put(ESCALATED, EnumSet.of(RESOLVED));
        transitions.put(RESOLVED, EnumSet.noneOf(ConversationStatus.class));
        transitions.put(ABANDONED, EnumSet.noneOf(ConversationStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /** The states reachable from this one. Empty for a terminal state. */
    public Set<ConversationStatus> allowedNext() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }

    /**
     * @param next the proposed target state, may be null
     * @return whether moving from this state to {@code next} is legal; false for null and false for
     *     a self-transition, since re-entering the state you are already in is always a bug rather
     *     than a no-op worth tolerating
     */
    public boolean canTransitionTo(ConversationStatus next) {
        return next != null && TRANSITIONS.get(this).contains(next);
    }

    /** Whether this state admits no further transitions. */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
