package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.SlaPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reference data read per skill by the SLA engine, and the yardstick the sla_ratio KPI is
 * measured against.
 */
@Repository
public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    Optional<SlaPolicy> findBySkillIdAndActiveTrue(UUID skillId);

    /** skill_id is null on the global fallback policy. */
    Optional<SlaPolicy> findBySkillIsNullAndActiveTrue();

    List<SlaPolicy> findByActiveTrue();
}
