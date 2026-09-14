package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Invoice;
import com.callverse.core.domain.enums.InvoiceStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** Drives the overdue sweep. */
    List<Invoice> findByStatusAndPeriodEndBefore(InvoiceStatus status, LocalDate before);
}
