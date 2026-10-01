package com.callverse.core.application.features.customer.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.TransactionSummary;
import java.util.List;
import java.util.Objects;

/**
 * Lists a customer's recent account movements ({@code OWNERSHIP_RULES.md} E2).
 *
 * <p><strong>Parked.</strong> No HTTP route calls this: the {@code /internal} route that did was
 * withdrawn on 2026-09-30 pending the AI-integration phase (git tag
 * {@code internal-tools-http-surface}), and roadmap phase FS-1 re-exposes it under {@code /api/v1}.
 *
 * <p><strong>The count is bounded here</strong>: 10 by default and at most 50. A statement is
 * denser than a monthly bill, so the bounds are wider than the invoices this replaced (3 and 12);
 * 50 still covers a month of ordinary activity and keeps a looping caller from turning the query
 * into a bulk export.
 *
 * <p>An unknown customer is a 404 rather than an empty list: a caller that mistyped an id must not
 * conclude that the customer has no activity.
 */
public class GetRecentTransactionsQueryHandler {

    static final int DEFAULT_COUNT = 10;
    static final int MAX_COUNT = 50;

    private final CustomerRecords customers;

    public GetRecentTransactionsQueryHandler(CustomerRecords customers) {
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
    }

    public List<TransactionSummary> handle(GetRecentTransactionsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        int count = query.count() == null ? DEFAULT_COUNT : query.count();
        if (count < 1 || count > MAX_COUNT) {
            throw new InvalidRequestException("count must be between 1 and %d".formatted(MAX_COUNT));
        }
        if (!customers.exists(query.customerId())) {
            throw new ResourceNotFoundException("Customer", query.customerId());
        }
        return customers.findRecentTransactions(query.customerId(), count);
    }
}
