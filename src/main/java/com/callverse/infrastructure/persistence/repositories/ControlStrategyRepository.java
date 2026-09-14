package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.ControlStrategy;
import com.callverse.core.domain.enums.StrategyKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Experiment reference data. The independent variable of every comparison, resolved by code
 * so results can be reported against a stable name.
 */
@Repository
public interface ControlStrategyRepository extends JpaRepository<ControlStrategy, UUID> {

    Optional<ControlStrategy> findByCode(String code);

    /** BASELINE strategies are what an RL policy is compared against. */
    List<ControlStrategy> findByKind(StrategyKind kind);
}
