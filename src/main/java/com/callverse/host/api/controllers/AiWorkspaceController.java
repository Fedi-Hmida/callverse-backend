package com.callverse.host.api.controllers;

import com.callverse.core.application.features.quality.queries.AiWorkspaceQueryHandler;
import com.callverse.core.application.features.quality.commands.RequestQualityEvaluationCommandHandler;
import com.callverse.core.application.features.simulation.commands.CreateDialogueScenarioCommandHandler;
import com.callverse.core.application.interfaces.AiWorkspaceRead;
import com.callverse.core.application.interfaces.ConversationSimulationStore.TranscriptMessage;
import com.callverse.host.api.dto.response.ConversationSimulationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
@PreAuthorize(Roles.SUPERVISION)
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class AiWorkspaceController {
    private final AiWorkspaceQueryHandler queries;
    private final CreateDialogueScenarioCommandHandler createScenario;
    private final RequestQualityEvaluationCommandHandler requestQuality;

    @GetMapping("/simulation-runs/options")
    @Operation(operationId = "getSimulationOptions", summary = "List existing scenarios and strategies")
    public AiWorkspaceRead.Options options() { return queries.options(); }

    @PostMapping("/simulation-runs/scenarios")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createDialogueScenario", summary = "Create a named single-dialogue scenario; no population or load simulation")
    public AiWorkspaceRead.ScenarioOption createScenario(@Valid @RequestBody CreateScenario request) {
        return createScenario.handle(request.name());
    }

    public record CreateScenario(@NotBlank @Size(max = 120) String name) {}

    @GetMapping("/simulation-runs")
    @Operation(operationId = "listRecentSimulationRuns", summary = "List the twenty most recent conversational runs")
    public List<ConversationSimulationResponse> runs() {
        return queries.recentRuns().stream().map(ConversationSimulationResponse::from).toList();
    }

    @GetMapping("/simulation-runs/{runId}/messages")
    @Operation(operationId = "getSimulationTranscript", summary = "Read a simulated dialogue without private agent traces")
    public List<TranscriptMessage> transcript(@PathVariable UUID runId) { return queries.transcript(runId); }

    @GetMapping("/supervision/conversations/{conversationId}/quality")
    @Operation(operationId = "getConversationQuality", summary = "Read persisted live quality evaluations; empty while unavailable")
    public List<AiWorkspaceRead.QualityView> quality(@PathVariable UUID conversationId) {
        return queries.quality(conversationId);
    }

    @PostMapping("/supervision/conversations/{conversationId}/quality")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "requestConversationQuality", summary = "Request or retry asynchronous quality analysis of a resolved conversation")
    public Map<String, String> requestQuality(@PathVariable UUID conversationId) {
        requestQuality.handle(conversationId);
        return Map.of("status", "ACCEPTED");
    }
}
