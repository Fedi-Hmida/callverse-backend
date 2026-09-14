package com.callverse.core.domain.enums;

/**
 * Classified purpose of a conversation, produced by the Customer Advisor agent and consumed
 * by skill-based routing. CHURN is separated from COMMERCIAL because a retention conversation
 * is routed and scored differently from a sales one.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum Intent {
    BILLING,
    TECHNICAL,
    COMMERCIAL,
    CHURN,
    OTHER
}
