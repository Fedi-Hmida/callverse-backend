package com.callverse.core.domain.enums;

/**
 * Whether a card debits the account immediately or draws on a credit line.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum CardType {
    DEBIT,
    CREDIT
}
