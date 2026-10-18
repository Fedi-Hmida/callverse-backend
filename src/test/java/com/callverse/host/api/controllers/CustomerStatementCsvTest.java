package com.callverse.host.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.callverse.core.application.features.customer.CustomerPortalHandler;
import com.callverse.core.application.interfaces.TransactionSummary;
import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerStatementCsvTest {
    @Test void formulaLikeMultilineLabelsCannotBecomeSpreadsheetExpressions() {
        var portal = mock(CustomerPortalHandler.class);
        LocalDate day = LocalDate.of(2026, 10, 18);
        when(portal.statement(day, day)).thenReturn(List.of(new TransactionSummary(
                UUID.randomUUID(), UUID.randomUUID(), TransactionType.REFUND, BigDecimal.ONE, "EUR",
                "=1+1\ncontinued", "\t@SUM(1;2)\r\ncontinued", TransactionStatus.BOOKED,
                Instant.parse("2026-10-18T10:00:00Z"))));
        String csv = new String(new CustomerPortalController(portal).statement(day, day).getBody(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=1+1\ncontinued\"", "\"'\t@SUM(1;2)\r\ncontinued\"");
    }
}
