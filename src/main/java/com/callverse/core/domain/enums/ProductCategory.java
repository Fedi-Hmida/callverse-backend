package com.callverse.core.domain.enums;

/**
 * Family a banking product belongs to. A loan is an account on a loan product, carrying the
 * outstanding balance, which is why loans sit here beside current and savings accounts.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum ProductCategory {
    CURRENT_ACCOUNT,
    SAVINGS,
    CONSUMER_LOAN,
    MORTGAGE
}
