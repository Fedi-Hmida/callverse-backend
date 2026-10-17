package com.callverse.core.domain.enums;

/**
 * Whether the AI Customer Advisor writes in live conversations.
 *
 * <p>{@code OFF} is the default: a live conversation belongs to its human advisor. {@code AUTONOMOUS}
 * lets the AI answer customer messages until a human advisor writes in that conversation; from then
 * on the human has taken over and the AI stays silent. A suggestion-only copilot mode is a later step.
 */
public enum AiAdvisorMode {
    OFF,
    AUTONOMOUS
}
