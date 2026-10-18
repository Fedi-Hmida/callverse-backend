package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.TwinRunStore.TwinScenario;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A scenario the digital twin can replay: load profile, length of the day, staff. */
@Schema(name = "Scenario")
public record ScenarioResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED, example = "Démo — journée chargée") String name,
        @Schema(requiredMode = REQUIRED, allowableValues = {"LOW", "MEDIUM", "SATURATED"}) String loadProfile,
        @Schema(requiredMode = REQUIRED, example = "480") int durationMinutes,
        @Schema(requiredMode = REQUIRED, example = "12", description = "Spread over the four skills by the twin")
                int advisorCount) {

    public static ScenarioResponse from(TwinScenario scenario) {
        return new ScenarioResponse(scenario.id(), scenario.name(), scenario.load().name(),
                scenario.durationMinutes(), scenario.advisorCount());
    }
}
