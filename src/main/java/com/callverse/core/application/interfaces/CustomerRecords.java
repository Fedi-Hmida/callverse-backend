package com.callverse.core.application.interfaces;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to a customer and what hangs off it, for the customer reads and the transaction history.
 *
 * <p>Returns read models, never entities. With {@code open-in-view} off, an entity handed out of the
 * adapter carries lazy associations that fail the moment a handler touches them; and an entity that
 * reaches a controller is one annotation away from being serialised whole, {@code churn_risk}
 * included. The adapter builds each record inside one read-only transaction.
 */
public interface CustomerRecords {

    /** @return the profile with every account and its product, or empty for an unknown id */
    Optional<CustomerProfile> findProfile(UUID customerId);

    /** @return the profile of the customer with this business reference, or empty */
    Optional<CustomerProfile> findProfileByExternalRef(String externalRef);

    boolean exists(UUID customerId);

    /**
     * The customer's most recent movements across <em>all</em> their accounts, newest first.
     *
     * <p>The path customer → account → transaction is resolved here: {@code bank_transaction} has
     * no {@code customer_id}, and no caller ever supplies an account id. That is what keeps another
     * customer's movements out ({@code OWNERSHIP_RULES.md} A4 and E2).
     */
    List<TransactionSummary> findRecentTransactions(UUID customerId, int limit);
}
