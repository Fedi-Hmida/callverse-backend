package com.callverse.host.api.dto.request;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.features.simulation.commands.RunDigitalTwinCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A digital twin run: which scenario, under which control strategy, with which seed. */
public record RunDigitalTwinRequest(
        @Schema(requiredMode = REQUIRED, description = "Scenario: load profile, duration and staff")
        @NotNull UUID scenarioId,
        @Schema(requiredMode = REQUIRED, example = "RL_PPO_V1",
                description = "Control strategy code: STATIC_FIFO, THRESHOLD or RL_PPO_V1")
        @NotBlank @Size(max = 40) String strategyCode,
        @Schema(requiredMode = REQUIRED, example = "7",
                description = "Seed of the simulated day: the same seed gives the same customers for every strategy")
        @NotNull @Min(0) Long seed) {

    public RunDigitalTwinCommand toCommand() {
        return new RunDigitalTwinCommand(scenarioId, strategyCode, seed);
    }
}
