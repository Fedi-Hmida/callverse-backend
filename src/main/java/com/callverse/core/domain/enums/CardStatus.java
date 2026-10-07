package com.callverse.core.domain.enums;

/**
 * Lifecycle of a card. BLOCKED is reversible (a card found again); CANCELLED is not.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum CardStatus {
    ACTIVE,
    BLOCKED,
    EXPIRED,
    CANCELLED
}
