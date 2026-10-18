package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Customer;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Customer aggregate root. Account is reached through Customer.getAccounts() and has no
 * repository of its own.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByExternalRef(String externalRef);

    /**
     * The customer, with its row locked until the transaction ends: two contacts opened for one
     * customer at the same instant queue here, so the second sees the first and is refused.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") UUID id);

    List<Customer> findByUserIdAndSimulatedFalse(UUID userId);

    /** Uses idx_customer_region. Outage triage cross-references this against active incidents. */
    List<Customer> findByRegion(String region);

    /**
     * Excludes simulated customers, which is the filter that keeps experiment traffic out of
     * business reporting. Callers reporting on real customers must use this rather than findAll.
     */
    List<Customer> findBySimulatedFalse();
}
