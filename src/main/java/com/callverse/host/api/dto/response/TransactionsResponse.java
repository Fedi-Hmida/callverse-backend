package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.TransactionSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A customer's recent movements, newest first. {@code label} and {@code counterparty} are free text
 * and often carry an IBAN ("VIR SEPA FR76 ..."), so both pass through {@link IbanMask#redact}.
 */
@Schema(name = "TransactionsResponse", description = "A customer's recent account movements, newest first")
public record TransactionsResponse(
        @Schema(requiredMode = REQUIRED) UUID customerId,
        @Schema(requiredMode = REQUIRED) List<Transaction> transactions) {

    @Schema(name = "Transaction", description = "One movement on an account")
    public record Transaction(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID accountId,
            @Schema(requiredMode = REQUIRED, example = "CARD_PAYMENT",
                    allowableValues = {"CARD_PAYMENT", "ATM_WITHDRAWAL", "TRANSFER_IN", "TRANSFER_OUT",
                        "DIRECT_DEBIT", "FEE", "INTEREST", "REFUND"})
                    String type,
            @Schema(requiredMode = REQUIRED, description = "Signed: negative debits the account", example = "-89.90")
                    BigDecimal amount,
            @Schema(requiredMode = REQUIRED, example = "EUR") String currency,
            @Schema(requiredMode = REQUIRED, description = "Any IBAN inside is masked", example = "CB MARKET 29/09") String label,
            @Schema(nullable = true, description = "Any IBAN inside is masked", example = "Loyer DE89 **** **** 3000")
                    String counterparty,
            @Schema(requiredMode = REQUIRED, example = "BOOKED",
                    allowableValues = {"PENDING", "BOOKED", "REJECTED", "DISPUTED"})
                    String status,
            @Schema(requiredMode = REQUIRED) Instant bookedAt) {}

    public static TransactionsResponse of(UUID customerId, List<TransactionSummary> summaries) {
        return new TransactionsResponse(
                customerId,
                summaries.stream()
                        .map(t -> new Transaction(
                                t.id(),
                                t.accountId(),
                                t.type().name(),
                                t.amount(),
                                t.currency(),
                                IbanMask.redact(t.label()),
                                IbanMask.redact(t.counterparty()),
                                t.status().name(),
                                t.bookedAt()))
                        .toList());
    }
}
