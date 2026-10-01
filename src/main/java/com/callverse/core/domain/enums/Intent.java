package com.callverse.core.domain.enums;

/**
 * Classified purpose of a conversation, produced by the Customer Advisor agent and consumed
 * by skill-based routing. The same vocabulary the AI service classifies into. FRAUD is separated
 * from CARD because a suspected fraud is routed to a stricter SLA than a card question.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. The column carries no CHECK constraint;
 * V3 rewrote the values it retired to OTHER, so that every stored value still loads.
 */
public enum Intent {
    BALANCE,
    CARD,
    CREDIT,
    FRAUD,
    ACCOUNT_CLOSURE,
    OTHER
}
