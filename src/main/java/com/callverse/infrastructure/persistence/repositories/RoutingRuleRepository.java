package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.RoutingRule;
import com.callverse.core.domain.enums.Intent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reference data evaluated by the routing engine. Rules are rows so that a supervisor can
 * change routing without a deployment.
 */
@Repository
public interface RoutingRuleRepository extends JpaRepository<RoutingRule, UUID> {

    /** Lower priority value wins, hence ascending. */
    List<RoutingRule> findByActiveTrueOrderByPriorityAsc();

    List<RoutingRule> findByIntentAndActiveTrueOrderByPriorityAsc(Intent intent);
}
