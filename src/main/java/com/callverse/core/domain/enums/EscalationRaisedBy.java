package com.callverse.core.domain.enums;

/**
 * What triggered an escalation: a human advisor asking for help, an AI agent recognising it
 * is out of depth, or an automatic rule such as an SLA breach.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum EscalationRaisedBy {
    ADVISOR,
    AI,
    RULE
}
