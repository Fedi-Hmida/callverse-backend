package com.callverse.core.application.features.customer.queries;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.core.application.interfaces.CustomerRecords;
import java.util.Objects;

/**
 * Answers the agent tool {@code GET /internal/customers/{id}} ({@code OWNERSHIP_RULES.md} E1).
 *
 * <p>No ownership check, by design: the AI service has no principal and legitimately reads any
 * customer it serves. What protects the data is the service key on the route and the projection in
 * {@link CustomerProfile}, which leaves out every field the agent has no use for.
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
