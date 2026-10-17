package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.features.workforce.commands.RequestWorkforceRecommendationCommandHandler
        .Recommendation;
import com.callverse.core.application.interfaces.WorkforceAgent.WorkforceState;
import com.callverse.core.domain.enums.SkillCode;
import com.callverse.core.domain.enums.WorkforceActionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A Workforce proposal and the exact live observation shown for human review. */
@Schema(name = "WorkforceRecommendation")
public record WorkforceRecommendationResponse(
        @Schema(requiredMode = REQUIRED) long decisionId,
        @Schema(requiredMode = REQUIRED) Instant generatedAt,
        @Schema(requiredMode = REQUIRED) WorkforceActionType type,
        SkillCode fromPool,
        SkillCode toPool,
        Integer count,
        @Schema(requiredMode = REQUIRED) String reason,
        @Schema(requiredMode = REQUIRED) Map<String, Double> expectedGain,
        @Schema(requiredMode = REQUIRED, description = "This proposal has not been applied and requires human review.")
                boolean requiresApproval,
        @Schema(requiredMode = REQUIRED) ObservedState observation,
        @Schema(requiredMode = REQUIRED) List<String> caveats) {

    public static WorkforceRecommendationResponse from(Recommendation recommendation) {
        WorkforceState state = recommendation.observation();
        List<String> caveats = new ArrayList<>(List.of(
                "No advisor reassignment is performed; a supervisor must review and apply any change.",
                "An advisor with multiple skills is counted in each eligible pool, so availability counts may overlap.",
                "Trend is currently a neutral model input (1.0), not a measured trend."));
        if (!state.slaMeasured()) {
            caveats.add("No SLA sample was measured today; the model received its training-compatible neutral value (1.0).");
        }
        var proposal = recommendation.proposal();
        return new WorkforceRecommendationResponse(
                recommendation.decisionId(),
                recommendation.generatedAt(),
                proposal.type(),
                proposal.fromPool(),
                proposal.toPool(),
                proposal.count(),
                proposal.reason(),
                proposal.expectedGain(),
                proposal.requiresApproval(),
                new ObservedState(
                        state.queues(),
                        state.averageWaitSeconds(),
                        state.available(),
                        state.slaToday(),
                        state.slaMeasured(),
                        state.hour(),
                        state.trend()),
                List.copyOf(caveats));
    }

    @Schema(name = "WorkforceObservedState")
    public record ObservedState(
            @Schema(requiredMode = REQUIRED) Map<SkillCode, Long> queues,
            @Schema(requiredMode = REQUIRED) Map<SkillCode, Double> averageWaitSeconds,
            @Schema(requiredMode = REQUIRED) Map<SkillCode, Integer> available,
            @Schema(requiredMode = REQUIRED) double slaToday,
            @Schema(requiredMode = REQUIRED) boolean slaMeasured,
            @Schema(requiredMode = REQUIRED) int hour,
            @Schema(requiredMode = REQUIRED) double trend) {}
}
