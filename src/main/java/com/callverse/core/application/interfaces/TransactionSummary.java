package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One account movement as a statement shows it. {@code amount} is signed: negative debits the
 * account.
 */
public record TransactionSummary(
        UUID id,
        UUID accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String label,
        String counterparty,
        TransactionStatus status,
        Instant bookedAt) {}
