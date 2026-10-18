package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.SkillCode;
import com.callverse.core.domain.enums.WorkforceActionType;
import java.util.Map;
import java.util.Objects;

/** Port to the Workforce Manager; it can propose actions but cannot execute them. */
public interface WorkforceAgent {

    WorkforceProposal decide(WorkforceState state);

    record WorkforceState(
            Map<SkillCode, Long> queues,
            Map<SkillCode, Double> averageWaitSeconds,
            Map<SkillCode, Integer> available,
            double slaToday,
            boolean slaMeasured,
            int hour,
            double trend) {

        public WorkforceState {
            queues = Map.copyOf(queues);
            averageWaitSeconds = Map.copyOf(averageWaitSeconds);
            available = Map.copyOf(available);
        }
    }

    record WorkforceProposal(
            WorkforceActionType type,
            SkillCode fromPool,
            SkillCode toPool,
            Integer count,
            String reason,
            Map<String, Double> expectedGain,
            String strategy,
            int actionId,
            boolean requiresApproval) {

        public WorkforceProposal {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            expectedGain = Map.copyOf(expectedGain);
            Objects.requireNonNull(strategy, "strategy must not be null");
            if (!requiresApproval) {
                throw new IllegalArgumentException("Workforce proposals must require human approval.");
            }
        }
    }
}
