package com.callverse.core.domain.enums;

/**
 * Whether a raised escalation has been dealt with by a supervisor.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum EscalationStatus {
    PENDING,
    RESOLVED
}
