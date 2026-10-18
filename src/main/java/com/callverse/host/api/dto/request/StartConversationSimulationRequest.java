package com.callverse.host.api.dto.request;

import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.SimulatorProfile;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Explicit, repeatable setup for one synthetic Simulator → Advisor conversation. */
public record StartConversationSimulationRequest(
        @NotNull UUID scenarioId,
        @NotBlank @Size(max = 40) String strategyCode,
        @NotNull Long seed,
        @NotNull SimulatorProfile profile,
        @NotBlank @Size(max = 1000) String objective,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double patience,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double satisfaction,
        @Min(1) @Max(50) int maxTurns,
        @NotNull CustomerSegment customerSegment,
        @NotNull @Min(0) Integer tenureMonths) {

    public SimulationSetup toSetup() {
        return new SimulationSetup(
                scenarioId,
                strategyCode,
                seed,
                profile,
                objective,
                patience,
                satisfaction,
                maxTurns,
                customerSegment,
                tenureMonths);
    }
}
