package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Plan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reference data with a lifecycle independent of any customer: plans are withdrawn, not
 * deleted, because live contracts still point at them.
 */
@Repository
public interface PlanRepository extends JpaRepository<Plan, UUID> {

    Optional<Plan> findByCode(String code);

    List<Plan> findByActiveTrue();
}
