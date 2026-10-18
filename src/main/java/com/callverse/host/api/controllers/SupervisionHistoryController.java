package com.callverse.host.api.controllers;

import com.callverse.core.application.features.conversation.queries.GetSupervisionAlertsQueryHandler;
import com.callverse.core.application.interfaces.SupervisionAlert;
import com.callverse.host.api.dto.response.UserResponse.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path="/api/v1/supervision/alerts", produces="application/json")
@PreAuthorize(Roles.SUPERVISION)
@SecurityRequirement(name="bearerAuth")
@RequiredArgsConstructor
public class SupervisionHistoryController {
    private final GetSupervisionAlertsQueryHandler alerts;

    @GetMapping
    @Operation(operationId="listSupervisionAlerts",summary="Browse persisted live alert history, newest first")
    public AlertHistoryResponse list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        var result=alerts.handle(page,size);
        return new AlertHistoryResponse(result.content(),new PageInfo(result.number(),result.size(),result.totalElements(),result.totalPages()));
    }

    @Schema(name="SupervisionAlertPage")
    public record AlertHistoryResponse(List<SupervisionAlert> content,PageInfo page) {}
}
