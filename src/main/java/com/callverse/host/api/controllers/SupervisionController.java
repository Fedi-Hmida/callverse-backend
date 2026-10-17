package com.callverse.host.api.controllers;

import com.callverse.core.application.features.conversation.queries.GetLiveKpiQueryHandler;
import com.callverse.core.application.features.conversation.queries.SearchConversationsQuery;
import com.callverse.core.application.features.conversation.queries.SearchConversationsQueryHandler;
import com.callverse.core.application.features.workforce.commands.RequestWorkforceRecommendationCommandHandler;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.host.api.dto.response.LiveKpiResponse;
import com.callverse.host.api.dto.response.SupervisedConversationResponse;
import com.callverse.host.api.dto.response.WorkforceRecommendationResponse;
import com.callverse.host.api.errorhandling.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Live supervision: the floor's figures, and every conversation on it. */
@RestController
@RequestMapping(path = "/api/v1/supervision", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Supervision", description = "Live supervision")
@SecurityRequirement(name = "bearerAuth")
public class SupervisionController {

    private final GetLiveKpiQueryHandler getLiveKpi;
    private final SearchConversationsQueryHandler searchConversations;
    private final RequestWorkforceRecommendationCommandHandler workforceRecommendation;

    @GetMapping("/kpi")
    @PreAuthorize(Roles.SUPERVISION)
    @Operation(
            operationId = "getLiveKpi",
            summary = "Today's live KPIs",
            description = "Queues now, plus today's average wait, SLA ratio, abandon rate and counts, for live "
                    + "conversations only. The same shape as each frame on /topic/supervision/kpi: load this "
                    + "once, then follow the topic.")
    public LiveKpiResponse kpi() {
        return LiveKpiResponse.from(getLiveKpi.handle());
    }

    @GetMapping("/conversations")
    @PreAuthorize(Roles.SUPERVISION)
    @Operation(
            operationId = "listSupervisedConversations",
            summary = "Find and browse live conversations",
            description = "Every live conversation, newest first, paged (page from 0, size 1 to 100, default 20). "
                    + "Optional filters: status (repeatable), skill, customerId, advisorId, q (customer name or "
                    + "reference, case-insensitive), from (queued at or after) and to (queued before), ISO-8601. "
                    + "Each row names the customer and the advisor (advisor null while queued) and carries the wait, "
                    + "handle time, SLA outcome, "
                    + "message count, last message time and whether an escalation is pending. Open a row with "
                    + "getConversation, listMessages and /topic/conversation/{id}. Supervisors and admins only.")
    public SupervisedConversationResponse.Page conversations(
            @RequestParam(name = "status", required = false) Set<ConversationStatus> statuses,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID advisorId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return SupervisedConversationResponse.Page.from(searchConversations.handle(
                new SearchConversationsQuery(statuses, skill, customerId, advisorId, q, from, to, page, size)));
    }

    @PostMapping("/workforce/recommendations")
    @PreAuthorize(Roles.SUPERVISION)
    @Operation(
            operationId = "requestWorkforceRecommendation",
            summary = "Request a human-reviewed Workforce recommendation",
            description = "Builds an observation from live queues and advisor capacity, asks the Workforce Manager "
                    + "for a proposal, and records it in the decision trace. It never changes assignments. "
                    + "Availability may overlap for advisors with multiple skills; neutral SLA/trend inputs are "
                    + "identified in the response.")
    @ApiResponse(
            responseCode = "200",
            description = "A human-review-only proposal was generated and recorded.",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = WorkforceRecommendationResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "The Workforce service is unavailable or returned an invalid proposal.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    public WorkforceRecommendationResponse workforceRecommendation() {
        return WorkforceRecommendationResponse.from(workforceRecommendation.handle());
    }
}
