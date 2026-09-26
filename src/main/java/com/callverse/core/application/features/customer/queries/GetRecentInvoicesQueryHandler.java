package com.callverse.core.application.features.customer.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.InvoiceSummary;
import java.util.List;
import java.util.Objects;

/**
 * Answers the agent tool {@code GET /internal/customers/{id}/invoices?n=} ({@code OWNERSHIP_RULES.md}
 * E2).
 *
 * <p><strong>The count is bounded here</strong>: 3 by default, the contract's own example, and at
 * most 12 — a year of monthly bills, more than a billing question needs, and small enough that a
 * looping agent cannot turn the tool into a bulk export.
 *
 * <p>An unknown customer is a 404 rather than an empty list: an agent that mistyped an id must not
 * conclude that the customer has never been billed.
 */
public class GetRecentInvoicesQueryHandler {

    static final int DEFAULT_COUNT = 3;
    static final int MAX_COUNT = 12;

    private final CustomerRecords customers;

    public GetRecentInvoicesQueryHandler(CustomerRecords customers) {
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
    }

    public List<InvoiceSummary> handle(GetRecentInvoicesQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        int count = query.count() == null ? DEFAULT_COUNT : query.count();
        if (count < 1 || count > MAX_COUNT) {
            throw new InvalidRequestException("n must be between 1 and %d".formatted(MAX_COUNT));
        }
        if (!customers.exists(query.customerId())) {
            throw new ResourceNotFoundException("Customer", query.customerId());
        }
        return customers.findRecentInvoices(query.customerId(), count);
    }
}
