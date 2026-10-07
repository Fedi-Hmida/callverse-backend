package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Today's live supervision banner. The same fields as each frame on {@code /topic/supervision/kpi},
 * so a screen loads this once and then follows the topic.
 */
@Schema(name = "LiveKpi")
public record LiveKpiResponse(
        @Schema(requiredMode = REQUIRED, example = "1") int schemaVersion,
        @Schema(requiredMode = REQUIRED) Instant at,
        @Schema(requiredMode = REQUIRED, description = "Local midnight in the operations time zone") Instant since,
        @Schema(requiredMode = REQUIRED) List<QueuesResponse.Queue> queues,
        @Schema(requiredMode = REQUIRED) long waitingTotal,
        @Schema(requiredMode = REQUIRED, description = "Conversations held now: assigned, active or escalated")
                long inService,
        @Schema(requiredMode = REQUIRED) long resolvedToday,
        @Schema(requiredMode = REQUIRED) long abandonedToday,
        @Schema(description = "Mean wait of today's assigned conversations, in seconds; null when none")
                Double averageWaitSeconds,
        @Schema(description = "Share of today's assigned conversations answered within the SLA target; null when none",
                example = "0.94")
                Double slaRatio,
        @Schema(description = "Abandoned / (resolved + abandoned) today; null when none ended", example = "0.05")
                Double abandonRate) {

    public static LiveKpiResponse from(LiveKpiSnapshot s) {
        return new LiveKpiResponse(s.schemaVersion(), s.at(), s.since(),
                s.queues().stream().map(QueuesResponse.Queue::from).toList(), s.waitingTotal(), s.inService(),
                s.resolvedToday(), s.abandonedToday(), s.averageWaitSeconds(), s.slaRatio(), s.abandonRate());
    }
}
