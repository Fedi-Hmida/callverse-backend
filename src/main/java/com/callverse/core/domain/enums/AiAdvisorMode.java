package com.callverse.core.domain.enums;

/**
 * Whether the AI Customer Advisor writes in live conversations.
 *
 * <p>{@code AUTONOMOUS} answers customer messages throughout every open conversation, including
 * waiting and escalated contacts, until the first human reply. {@code OFF} explicitly
 * disables automatic replies. Human advisors can continue to participate in either mode.
 */
public enum AiAdvisorMode {
    OFF,
    AUTONOMOUS
}
