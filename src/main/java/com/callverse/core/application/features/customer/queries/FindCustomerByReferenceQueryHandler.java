package com.callverse.core.application.features.customer.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.CustomerReferenceNotFoundException;
import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.core.application.interfaces.CustomerRecords;
import java.util.Objects;

/**
 * Finds a customer by the reference they give on the phone: the first thing an advisor does on a
 * call. Exact match only — a partial or fuzzy search over customers is a bulk-read tool, and nothing
 * on the advisor console needs one.
 *
 * <p>An unknown reference is a 404, never an empty profile.
 */
public class FindCustomerByReferenceQueryHandler {

    static final int MAX_LENGTH = 40;

    private final CustomerRecords customers;

    public FindCustomerByReferenceQueryHandler(CustomerRecords customers) {
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
    }

    public CustomerProfile handle(FindCustomerByReferenceQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String reference = query.externalRef() == null ? "" : query.externalRef().trim();
        if (reference.isEmpty() || reference.length() > MAX_LENGTH) {
            throw new InvalidRequestException("externalRef must be 1 to %d characters".formatted(MAX_LENGTH));
        }
        return customers
                .findProfileByExternalRef(reference)
                .orElseThrow(CustomerReferenceNotFoundException::new);
    }
}
