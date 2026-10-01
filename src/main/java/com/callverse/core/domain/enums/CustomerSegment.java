package com.callverse.core.domain.enums;

/**
 * Commercial segment of a customer: the "client value" factor of the queue priority score.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum CustomerSegment {
    MASS,
    AFFLUENT,
    PRIVATE,
    PROFESSIONAL
}
