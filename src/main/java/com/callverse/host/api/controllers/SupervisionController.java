package com.callverse.host.api.controllers;

import com.callverse.core.application.features.conversation.queries.GetLiveKpiQueryHandler;
import com.callverse.host.api.dto.response.LiveKpiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live supervision figures. */
@RestController
@RequestMapping(path = "/api/v1/supervision", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Supervision", description = "Live supervision")
@SecurityRequirement(name = "bearerAuth")
public class SupervisionController {

    private final GetLiveKpiQueryHandler getLiveKpi;

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
}
