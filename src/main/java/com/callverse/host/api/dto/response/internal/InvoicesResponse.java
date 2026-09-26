package com.callverse.host.api.dto.response.internal;

import com.callverse.core.application.interfaces.InvoiceSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tool response for {@code GET /internal/customers/{id}/invoices}. <strong>Provisional</strong>, as
 * for every {@code /internal} shape. Wrapped in an object rather than a bare array so that fields can
 * be added later without breaking the agent's parser.
 */
@Schema(description = "A customer's most recent invoices, newest billing period first. Provisional.")
public record InvoicesResponse(UUID customerId, List<InvoiceResponse> invoices) {

    public record InvoiceResponse(
            UUID id,
            UUID contractId,
            LocalDate periodStart,
            LocalDate periodEnd,
            @Schema(example = "52.40") BigDecimal amount,
            @Schema(example = "OVERDUE") String status,
            Instant issuedAt) {}

    public static InvoicesResponse from(UUID customerId, List<InvoiceSummary> invoices) {
        return new InvoicesResponse(
                customerId,
                invoices.stream()
                        .map(i -> new InvoiceResponse(
                                i.id(),
                                i.contractId(),
                                i.periodStart(),
                                i.periodEnd(),
                                i.amount(),
                                i.status().name(),
                                i.issuedAt()))
                        .toList());
    }
}
