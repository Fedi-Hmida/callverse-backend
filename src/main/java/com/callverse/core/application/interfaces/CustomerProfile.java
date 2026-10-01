package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.ProductCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What staff need to know about a customer to handle a request, and deliberately nothing else.
 *
 * <p><strong>Absent on purpose.</strong> {@code churn_risk} feeds the queue's priority score, so a
 * caller that sees it can learn to game the queue. {@code is_simulated} would reveal an experiment.
 * {@code user_id} and {@code phone} are not needed to resolve a request.
 *
 * <p>{@code iban} is carried whole because this is an internal read model; the host layer decides
 * what leaves the backend, and the customer route masks it.
 */
public record CustomerProfile(
        UUID id,
        String externalRef,
        String firstName,
        String lastName,
        String region,
        CustomerSegment segment,
        int tenureMonths,
        List<Account> accounts) {

    public record Account(
            UUID id,
            String iban,
            String currency,
            BigDecimal balance,
            BigDecimal overdraftLimit,
            AccountStatus status,
            LocalDate openedAt,
            LocalDate closedAt,
            Product product) {}

    public record Product(String code, String name, ProductCategory category) {}
}
