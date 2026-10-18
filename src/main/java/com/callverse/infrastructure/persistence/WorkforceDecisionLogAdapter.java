package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.WorkforceAgent;
import com.callverse.core.application.interfaces.WorkforceDecisionLog;
import com.callverse.core.domain.entities.AgentDecision;
import com.callverse.core.domain.enums.AgentType;
import com.callverse.infrastructure.persistence.repositories.AgentDecisionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Writes unapproved live Workforce proposals to the existing explainability table. */
@Component
class WorkforceDecisionLogAdapter implements WorkforceDecisionLog {

    private final AgentDecisionRepository decisions;
    private final ObjectMapper objectMapper;

    WorkforceDecisionLogAdapter(AgentDecisionRepository decisions, ObjectMapper objectMapper) {
        this.decisions = decisions;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public long record(WorkforceAgent.WorkforceState observation, WorkforceAgent.WorkforceProposal proposal, Instant at) {
        AgentDecision decision = new AgentDecision();
        decision.setAgentType(AgentType.WORKFORCE_MANAGER);
        decision.setObservation(objectMapper.valueToTree(observation));
        decision.setAction(objectMapper.valueToTree(proposal));
        decision.setReason(proposal.reason());
        decision.setCreatedAt(at);
        return decisions.save(decision).getId();
    }
}
