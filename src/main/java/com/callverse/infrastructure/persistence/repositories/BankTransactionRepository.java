package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.BankTransaction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Its own aggregate root, unlike Account: statements and disputes read movements by account, and
 * idx_txn_account_booked serves that directly.
 */
@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    /**
     * A customer's movements across every account, most recent first. It walks transaction →
     * account → customer, because {@code bank_transaction} has no {@code customer_id}; the customer
     * is the only input, so no caller-chosen account can widen it.
     *
     * <p>{@code t.id} breaks ties between movements booked at the same instant, so paging is
     * deterministic. A customer holds a handful of accounts; whether the planner merges
     * {@code idx_txn_account_booked} per account or sorts is not asserted anywhere.
     *
     * @param pageable {@code PageRequest.of(0, n)}: only the first {@code n} are fetched
     */
    @Query("""
           select t
             from BankTransaction t
            where t.account.customer.id = :customerId
            order by t.bookedAt desc, t.id desc
           """)
    List<BankTransaction> findRecentForCustomer(
            @Param("customerId") UUID customerId, Pageable pageable);
}
