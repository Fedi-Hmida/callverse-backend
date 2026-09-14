package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.AgentDecision;
import com.callverse.core.domain.enums.AgentType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The explainability trace. Read by run and simulation time, which is exactly
 * idx_decision_run.
 */
@Repository
public interface AgentDecisionRepository extends JpaRepository<AgentDecision, Long> {

    /** Uses idx_decision_run (run_id, sim_time). */
    List<AgentDecision> findByRunIdOrderBySimTimeAsc(UUID runId);

    List<AgentDecision> findByRunIdAndAgentTypeOrderBySimTimeAsc(UUID runId, AgentType agentType);

    /** Human-in-the-loop audit: decisions a supervisor endorsed rather than the agent acting alone. */
    List<AgentDecision> findByApprovedByIsNotNull();
}
