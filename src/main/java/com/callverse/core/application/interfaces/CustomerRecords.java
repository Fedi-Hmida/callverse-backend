package com.callverse.core.application.interfaces;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to a customer and what hangs off it, for the agent tools.
 *
 * <p>Returns read models, never entities. With {@code open-in-view} off, an entity handed out of the
 * adapter carries lazy associations that fail the moment a handler touches them; and an entity that
 * reaches a controller is one annotation away from being serialised whole, {@code churn_risk}
 * included. The adapter builds each record inside one read-only transaction.
 */
public interface CustomerRecords {

    /** @return the profile with every contract and its plan, or empty for an unknown id */
    Optional<CustomerProfile> findProfile(UUID customerId);

    boolean exists(UUID customerId);

    /**
     * The customer's most recent invoices across <em>all</em> their contracts, newest period first.
     *
     * <p>The path customer → contract → invoice is resolved here: {@code invoice} has no
     * {@code customer_id}, and no caller ever supplies a contract id. That is what keeps another
     * customer's invoices out ({@code OWNERSHIP_RULES.md} A4 and E2).
     */
    List<InvoiceSummary> findRecentInvoices(UUID customerId, int limit);
}
