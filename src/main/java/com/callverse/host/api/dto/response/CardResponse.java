package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.Cards.CardRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A payment card. The last four digits are the only part of the card number that exists. */
@Schema(name = "CardResponse", description = "A payment card")
public record CardResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) UUID accountId,
        @Schema(requiredMode = REQUIRED, example = "4242") String panLast4,
        @Schema(requiredMode = REQUIRED, example = "VISA", allowableValues = {"VISA", "MASTERCARD"}) String network,
        @Schema(requiredMode = REQUIRED, example = "DEBIT", allowableValues = {"DEBIT", "CREDIT"}) String type,
        @Schema(requiredMode = REQUIRED, example = "BLOCKED",
                allowableValues = {"ACTIVE", "BLOCKED", "EXPIRED", "CANCELLED"})
                String status,
        @Schema(requiredMode = REQUIRED) LocalDate expiresOn,
        @Schema(nullable = true, description = "Set when the card was blocked") Instant blockedAt,
        @Schema(nullable = true, example = "STOLEN",
                allowableValues = {"LOST", "STOLEN", "FRAUD_SUSPECTED", "CUSTOMER_REQUEST"})
                String blockReason) {

    public static CardResponse from(CardRecord c) {
        return new CardResponse(
                c.id(), c.accountId(), c.panLast4(), c.network().name(), c.type().name(), c.status().name(),
                c.expiresOn(), c.blockedAt(), c.blockReason() == null ? null : c.blockReason().name());
    }
}
