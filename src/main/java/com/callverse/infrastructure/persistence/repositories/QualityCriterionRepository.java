package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.QualityCriterion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reference data: the configurable grid. Its codes are the keys inside
 * quality_evaluation.scores, so they are effectively a contract.
 */
@Repository
public interface QualityCriterionRepository extends JpaRepository<QualityCriterion, UUID> {

    Optional<QualityCriterion> findByCode(String code);

    List<QualityCriterion> findByActiveTrue();
}
