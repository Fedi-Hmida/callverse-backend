package com.callverse.core.domain.enums;

/**
 * Where a transaction stands. DISPUTED is first-class because a contested card payment is one
 * of the most common reasons a customer opens a conversation.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum TransactionStatus {
    PENDING,
    BOOKED,
    REJECTED,
    DISPUTED
}
