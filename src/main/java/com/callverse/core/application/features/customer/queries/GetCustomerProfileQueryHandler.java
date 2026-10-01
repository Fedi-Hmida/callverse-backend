package com.callverse.core.application.features.customer.queries;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.core.application.interfaces.CustomerRecords;
import java.util.Objects;

/**
 * Reads a customer's profile for {@code GET /api/v1/customers/{id}} ({@code OWNERSHIP_RULES.md} E1).
 *
 * <p>No ownership check, by design: the only caller today is ADMIN-gated, and ADMIN legitimately
 * reads any customer. What protects the data is the route's role gate and the projection in
 * {@link CustomerProfile}, which leaves out {@code churn_risk}. It also answered the agent tool
 * {@code GET /internal/customers/{id}} until that surface was withdrawn on 2026-09-30 (git tag
 * {@code internal-tools-http-surface}).
 */
public class GetCustomerProfileQueryHandler {

    private final CustomerRecords customers;

    public GetCustomerProfileQueryHandler(CustomerRecords customers) {
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
    }

    public CustomerProfile handle(GetCustomerProfileQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return customers
                .findProfile(query.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", query.customerId()));
    }
}
