package com.callverse.core.domain.enums;

/**
 * Which of the four autonomous agents produced a decision. Recorded on every agent_decision
 * row so that a Workforce Manager action can be traced back to what it observed and why.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum AgentType {
    CLIENT_SIMULATOR,
    CUSTOMER_ADVISOR,
    WORKFORCE_MANAGER,
    QUALITY_ANALYST
}
