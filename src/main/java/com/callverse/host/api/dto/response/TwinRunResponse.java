package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.TwinRunStore.RunKpis;
import com.callverse.core.application.interfaces.TwinRunStore.TwinRun;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** A digital twin run and, once completed, the KPIs it keeps. */
@Schema(name = "TwinRun")
public record TwinRunResponse(
        @Schema(requiredMode = REQUIRED) UUID runId,
        @Schema(requiredMode = REQUIRED) UUID scenarioId,
        @Schema(requiredMode = REQUIRED, example = "RL_PPO_V1") String strategyCode,
        @Schema(requiredMode = REQUIRED, example = "7") long seed,
        @Schema(requiredMode = REQUIRED, allowableValues = {"PENDING", "RUNNING", "COMPLETED", "FAILED"})
                String status,
        @Schema(description = "Safe failure reason when FAILED") String errorMessage,
        Instant startedAt,
        Instant endedAt,
        @Schema(description = "Null until COMPLETED") Kpis kpis) {

    /**
     * @param slaRatio answered within the SLA over all arrivals, abandons included (the reference SLA)
     */
    @Schema(name = "TwinRunKpis")
    public record Kpis(
            @Schema(requiredMode = REQUIRED, description = "Customers who arrived during the simulated day")
                    int totalConversations,
            @Schema(requiredMode = REQUIRED, example = "0.8512") double slaRatio,
            @Schema(requiredMode = REQUIRED, example = "0.0673") double abandonRatio,
            @Schema(requiredMode = REQUIRED, example = "41.23") double avgWaitSeconds,
            @Schema(requiredMode = REQUIRED, example = "158.79") double p95WaitSeconds) {}

    public static TwinRunResponse from(TwinRun run) {
        RunKpis kpis = run.kpis();
        return new TwinRunResponse(
                run.runId(),
                run.scenarioId(),
                run.strategyCode(),
                run.seed(),
                run.status().name(),
                run.errorMessage(),
                run.startedAt(),
                run.endedAt(),
                kpis == null ? null : new Kpis(kpis.totalConversations(), kpis.slaRatio(), kpis.abandonRatio(),
                        kpis.avgWaitSeconds(), kpis.p95WaitSeconds()));
    }
}
