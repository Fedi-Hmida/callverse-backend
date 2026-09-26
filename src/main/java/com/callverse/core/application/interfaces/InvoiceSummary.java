package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.InvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One invoice as the agent sees it: the billing period, the amount and whether it is paid. */
public record InvoiceSummary(
        UUID id,
        UUID contractId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal amount,
        InvoiceStatus status,
        Instant issuedAt) {}
