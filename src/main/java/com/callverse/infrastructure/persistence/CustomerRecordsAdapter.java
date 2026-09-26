package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.InvoiceSummary;
import com.callverse.core.domain.entities.Contract;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Invoice;
import com.callverse.core.domain.entities.Plan;
import com.callverse.infrastructure.persistence.repositories.CustomerRepository;
import com.callverse.infrastructure.persistence.repositories.InvoiceRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link CustomerRecords} with Spring Data.
 *
 * <p><strong>Read-only transaction around every method</strong>, because the profile walks two lazy
 * associations (contracts, then each contract's plan) and {@code open-in-view} is off: outside a
 * transaction the first of them would throw. The records are complete when they leave this class,
 * so nothing lazy escapes. The walk is one query per contract for the plan; a customer has a handful
 * of contracts, so a fetch join is not worth its complexity here.
 *
 * <p>Package-private: callers name the port, never the adapter.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CustomerRecordsAdapter implements CustomerRecords {

    private final CustomerRepository customers;
    private final InvoiceRepository invoices;

    @Override
    public Optional<CustomerProfile> findProfile(UUID customerId) {
        return customers.findById(customerId).map(CustomerRecordsAdapter::toProfile);
    }

    @Override
    public boolean exists(UUID customerId) {
        return customers.existsById(customerId);
    }

    @Override
    public List<InvoiceSummary> findRecentInvoices(UUID customerId, int limit) {
        return invoices.findRecentForCustomer(customerId, PageRequest.of(0, limit)).stream()
                .map(CustomerRecordsAdapter::toSummary)
                .toList();
    }

    private static CustomerProfile toProfile(Customer customer) {
        List<CustomerProfile.Contract> contracts =
                customer.getContracts().stream()
                        // Newest first, so the contract the customer is most likely calling about leads.
                        .sorted(Comparator.comparing(Contract::getStartedAt).reversed())
                        .map(CustomerRecordsAdapter::toContract)
                        .toList();
        return new CustomerProfile(
                customer.getId(),
                customer.getExternalRef(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getZone(),
                customer.getTenureMonths(),
                contracts);
    }

    private static CustomerProfile.Contract toContract(Contract contract) {
        Plan plan = contract.getPlan();
        return new CustomerProfile.Contract(
                contract.getId(),
                contract.getStatus(),
                contract.getStartedAt(),
                contract.getEndedAt(),
                new CustomerProfile.Plan(
                        plan.getCode(),
                        plan.getName(),
                        plan.getCategory(),
                        plan.getMonthlyPrice(),
                        plan.getDataGb(),
                        plan.getSpeedMbps()));
    }

    private static InvoiceSummary toSummary(Invoice invoice) {
        return new InvoiceSummary(
                invoice.getId(),
                // Reading the id of a lazy proxy does not initialise it.
                invoice.getContract().getId(),
                invoice.getPeriodStart(),
                invoice.getPeriodEnd(),
                invoice.getAmount(),
                invoice.getStatus(),
                invoice.getIssuedAt());
    }
}
