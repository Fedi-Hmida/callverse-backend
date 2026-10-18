package com.callverse.host.api.controllers;

import com.callverse.core.application.features.simulation.queries.GetDigitalTwinRunsQueryHandler;
import com.callverse.host.api.dto.response.ScenarioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The scenarios the digital twin can replay; read-only (no scenario editor yet). */
@RestController
@RequestMapping(path = "/api/v1/scenarios", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Digital twin", description = "Scenario runs under a control strategy, and their comparison")
@SecurityRequirement(name = "bearerAuth")
public class ScenarioController {

    private final GetDigitalTwinRunsQueryHandler twin;

    @GetMapping
    @PreAuthorize(Roles.SUPERVISION)
    @Operation(
            operationId = "listScenarios",
            summary = "List the digital twin scenarios",
            description = "Every scenario by name, with its load profile, duration and advisor count: what the "
                    + "twin screen offers to run. Supervisors and admins only.")
    public List<ScenarioResponse> list() {
        return twin.scenarios().stream().map(ScenarioResponse::from).toList();
    }
}
