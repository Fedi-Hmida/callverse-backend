package com.callverse.core.domain.enums;

/**
 * Lifecycle of an account. FROZEN keeps the account and its history but refuses movements, the
 * state a fraud investigation or a legal hold puts it in.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
