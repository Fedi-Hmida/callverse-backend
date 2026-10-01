package com.callverse.core.domain.enums;

/**
 * Payment network a card is issued on.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum CardNetwork {
    VISA,
    MASTERCARD
}
