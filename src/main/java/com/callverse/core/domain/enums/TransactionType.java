package com.callverse.core.domain.enums;

/**
 * Kind of movement on an account.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum TransactionType {
    CARD_PAYMENT,
    ATM_WITHDRAWAL,
    TRANSFER_IN,
    TRANSFER_OUT,
    DIRECT_DEBIT,
    FEE,
    INTEREST,
    REFUND
}
