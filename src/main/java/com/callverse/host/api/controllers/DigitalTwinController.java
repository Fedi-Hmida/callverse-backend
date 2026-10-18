package com.callverse.host.api.controllers;

import com.callverse.core.application.features.simulation.commands.RunDigitalTwinCommandHandler;
import com.callverse.core.application.features.simulation.queries.GetDigitalTwinRunsQueryHandler;
import com.callverse.host.api.dto.request.RunDigitalTwinRequest;
import com.callverse.host.api.dto.response.TwinRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The digital twin: replay a scenario under a control strategy and compare the strategies. */
@RestController
@RequestMapping(path = "/api/v1/twin-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Digital twin", description = "Scenario runs under a control strategy, and their comparison")
@SecurityRequirement(name = "bearerAuth")
public class DigitalTwinController {

    private final RunDigitalTwinCommandHandler runTwin;
    private final GetDigitalTwinRunsQueryHandler getRuns;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.SUPERVISION)
    @ApiResponse(responseCode = "201", description = "Run completed; its KPIs are stored")
    @Operation(
            operationId = "runDigitalTwin",
            summary = "Replay a scenario under a control strategy",
            description = "Simulates the scenario's day (load profile, duration, advisors spread over the four "
                    + "skills) under STATIC_FIFO, THRESHOLD or RL_PPO_V1 with the given seed, and stores the KPIs. "
                    + "The same seed replays the same customers, so runs with different strategies compare fairly. "
                    + "A scenario, strategy and seed run once (409). If the twin is unavailable the run is kept "
                    + "as FAILED and the call answers 503 WORKFORCE_UNAVAILABLE. Supervisors and admins only.")
    public ResponseEntity<TwinRunResponse> run(@Valid @RequestBody RunDigitalTwinRequest request) {
        TwinRunResponse response = TwinRunResponse.from(runTwin.handle(request.toCommand()));
        return ResponseEntity.created(URI.create("/api/v1/twin-runs/" + response.runId())).body(response);
    }

    @GetMapping("/{runId}")
    @PreAuthorize(Roles.SUPERVISION)
    @Operation(
            operationId = "getDigitalTwinRun",
            summary = "Get a digital twin run",
            description = "A conversation simulation run is not a twin run: its id answers 404 here.")
    public TwinRunResponse get(@PathVariable UUID runId) {
        return TwinRunResponse.from(getRuns.byId(runId));
    }

    @GetMapping
    @PreAuthorize(Roles.SUPERVISION)
    @Operation(
            operationId = "compareDigitalTwinRuns",
            summary = "Compare the strategies on a scenario",
            description = "The scenario's completed twin runs with their KPIs, ordered by strategy then seed.")
    public List<TwinRunResponse> compare(@RequestParam UUID scenarioId) {
        return getRuns.comparison(scenarioId).stream().map(TwinRunResponse::from).toList();
    }
}
