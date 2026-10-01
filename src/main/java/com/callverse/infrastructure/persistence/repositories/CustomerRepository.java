package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Customer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Customer aggregate root. Account is reached through Customer.getAccounts() and has no
 * repository of its own.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByExternalRef(String externalRef);

    /** Uses idx_customer_region. Outage triage cross-references this against active incidents. */
    List<Customer> findByRegion(String region);

    /**
     * Excludes simulated customers, which is the filter that keeps experiment traffic out of
     * business reporting. Callers reporting on real customers must use this rather than findAll.
     */
    List<Customer> findBySimulatedFalse();
}
