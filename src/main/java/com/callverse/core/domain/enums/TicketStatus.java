package com.callverse.core.domain.enums;

/**
 * Lifecycle state of a support ticket. A ticket outlives the conversation that spawned it,
 * which is why RESOLVED and CLOSED are distinct: resolved is the advisor's claim, closed is
 * the customer's acceptance.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED
}
