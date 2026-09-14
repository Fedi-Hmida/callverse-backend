package com.callverse.core.domain.enums;

/**
 * Churn propensity band for a customer. Feeds priority scoring, so that a conversation from a
 * customer about to leave is not queued behind a routine billing question.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum ChurnRisk {
    LOW,
    MEDIUM,
    HIGH
}
