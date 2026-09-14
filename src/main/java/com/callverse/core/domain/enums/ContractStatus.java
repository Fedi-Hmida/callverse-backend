package com.callverse.core.domain.enums;

/**
 * Lifecycle state of a customer's contract. SUSPENDED is reversible; TERMINATED is not.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum ContractStatus {
    ACTIVE,
    SUSPENDED,
    TERMINATED
}
