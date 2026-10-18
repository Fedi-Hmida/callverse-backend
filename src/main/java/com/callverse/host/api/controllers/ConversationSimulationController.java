package com.callverse.host.api.controllers;

import com.callverse.core.application.features.simulation.commands.StartConversationSimulationCommandHandler;
import com.callverse.core.application.features.simulation.queries.GetConversationSimulationQueryHandler;
import com.callverse.host.api.dto.request.StartConversationSimulationRequest;
import com.callverse.host.api.dto.response.ConversationSimulationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RestController;

/** Supervisor-only endpoints for isolated synthetic conversations. */
@RestController
@RequestMapping(path = "/api/v1/simulation-runs", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Conversation simulations", description = "Isolated Client Simulator and Advisor runs")
@SecurityRequirement(name = "bearerAuth")
public class ConversationSimulationController {

    private final StartConversationSimulationCommandHandler startSimulation;
    private final GetConversationSimulationQueryHandler getSimulation;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.SUPERVISION)
    @ApiResponse(responseCode = "202", description = "Accepted; poll the Location URL for progress")
    @Operation(
            operationId = "startConversationSimulation",
            summary = "Start an isolated Simulator → Advisor dialogue",
            description = "Creates a synthetic customer and simulation-only conversation, then schedules the "
                    + "Simulator and Advisor asynchronously. Advisor backend tools are disabled in simulation mode. "
                    + "Poll the returned location for progress. The selected scenario, strategy and seed must be unique.")
    public ResponseEntity<ConversationSimulationResponse> start(
            @Valid @RequestBody StartConversationSimulationRequest request) {
        ConversationSimulationResponse response =
                ConversationSimulationResponse.from(startSimulation.handle(request.toSetup()));
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/simulation-runs/" + response.runId()))
                .body(response);
    }

    @GetMapping("/{runId}")
    @PreAuthorize(Roles.SUPERVISION)
    @Operation(
            operationId = "getConversationSimulation",
            summary = "Get a conversational simulation run",
            description = "Returns its state and counters. FAILED reports the safe failure reason without AI payloads.")
    public ConversationSimulationResponse get(@PathVariable UUID runId) {
        return ConversationSimulationResponse.from(getSimulation.handle(runId));
    }
}
