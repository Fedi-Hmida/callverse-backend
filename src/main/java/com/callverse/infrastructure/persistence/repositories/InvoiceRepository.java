package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Invoice;
import com.callverse.core.domain.enums.InvoiceStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Its own aggregate root, unlike Contract: the billing lifecycle runs with no customer in
 * hand, and idx_invoice_contract_period exists to serve it directly.
 */
@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /**
     * Uses idx_invoice_contract_period (contract_id, period_start DESC) end to end: equality on the
     * contract, then the index's own descending order, so no sort is needed.
     */
    List<Invoice> findByContractIdOrderByPeriodStartDesc(UUID contractId);

    /**
     * A customer's invoices across every contract, newest billing period first — the agent tool's
     * query. It walks invoice → contract → customer, because {@code invoice} has no
     * {@code customer_id}; the customer is the only input, so no caller-chosen contract can widen it.
     *
     * <p><strong>Index usage.</strong> The join reaches {@code contract} through its primary key and
     * {@code invoice} through {@code idx_invoice_contract_period}, but the cross-contract ordering is
     * a sort over the customer's invoices. A customer has a handful of contracts and a few dozen
     * invoices, so that sort is trivial; no new index is warranted.
     *
     * @param pageable {@code PageRequest.of(0, n)}: only the first {@code n} are fetched
     */
    @Query("""
           select i
             from Invoice i
            where i.contract.customer.id = :customerId
            order by i.periodStart desc, i.issuedAt desc
           """)
    List<Invoice> findRecentForCustomer(@Param("customerId") UUID customerId, Pageable pageable);

    /** Drives the overdue sweep. */
    List<Invoice> findByStatusAndPeriodEndBefore(InvoiceStatus status, LocalDate before);
}
