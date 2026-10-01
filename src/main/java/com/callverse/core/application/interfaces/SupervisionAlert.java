package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.UUID;

/**
 * Something a supervisor must see the moment it happens, published on
 * {@code /topic/supervision/alerts}.
 *
 * <p><strong>A published contract, like the REST API:</strong> the Next.js client parses it. Every
 * alert has the same versioned field set, unused fields are null, and the set is pinned by a test.
 * Add a {@link Type} rather than a field; bump {@link #SCHEMA_VERSION} if a field must change.
 *
 * <p><strong>Identifiers only.</strong> An alert says what happened and to whom, never the details:
 * no reason text, no IBAN, no balance — the card is named by its last four digits. The supervisor's
 * screen fetches anything else through the role-gated REST routes, so an alert intercepted or logged
 * by mistake carries almost nothing.
 */
public record SupervisionAlert(
        int schemaVersion,
        Type type,
        Instant occurredAt,
        UUID customerId,
        UUID conversationId,
        UUID escalationId,
        UUID cardId,
        String cardLast4) {

    public static final int SCHEMA_VERSION = 1;

    public enum Type {
        /** An advisor asked a supervisor to take over a conversation. */
        ESCALATION_RAISED,
        /** A card was blocked because fraud is suspected. */
        CARD_BLOCKED_FRAUD
    }

    public static SupervisionAlert escalationRaised(
            UUID escalationId, UUID conversationId, UUID customerId, Instant occurredAt) {
        return new SupervisionAlert(
                SCHEMA_VERSION, Type.ESCALATION_RAISED, occurredAt, customerId, conversationId, escalationId, null, null);
    }

    public static SupervisionAlert cardBlockedForFraud(
            UUID cardId, UUID customerId, String cardLast4, Instant occurredAt) {
        return new SupervisionAlert(
                SCHEMA_VERSION, Type.CARD_BLOCKED_FRAUD, occurredAt, customerId, null, null, cardId, cardLast4);
    }
}
