package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.SkillCode;
import java.time.Instant;
import java.util.Map;

/** Live queue and staffing inputs used to build a Workforce Manager observation. */
public interface WorkforceOperations {

    WorkforceInputs inputs(Instant now);

    record WorkforceInputs(
            Map<SkillCode, Long> queues,
            Map<SkillCode, Double> averageWaitSeconds,
            Map<SkillCode, Integer> available) {

        public WorkforceInputs {
            queues = Map.copyOf(queues);
            averageWaitSeconds = Map.copyOf(averageWaitSeconds);
            available = Map.copyOf(available);
        }
    }
}
