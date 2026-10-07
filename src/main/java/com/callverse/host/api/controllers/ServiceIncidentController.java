package com.callverse.host.api.controllers;

import com.callverse.core.application.features.incident.queries.GetServiceStatusQuery;
import com.callverse.core.application.features.incident.queries.GetServiceStatusQueryHandler;
import com.callverse.host.api.dto.response.ServiceIncidentsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Active banking-service outages: the "is it me or is it the bank?" banner. Live system only: no
 * parameter on this route can reach a simulation run's incidents.
 */
@RestController
@RequestMapping(path = "/api/v1/service-incidents", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Service incidents", description = "Active outages of banking services")
public class ServiceIncidentController {

    private final GetServiceStatusQueryHandler getServiceStatus;

    @GetMapping
    @PreAuthorize(Roles.ANYONE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "listActiveServiceIncidents",
            summary = "List active service outages",
            description = "Live outages for a region plus national ones; every active outage when no region "
                    + "is given. Any signed-in user. A blank region is 400 VALIDATION_FAILED.")
    public ServiceIncidentsResponse active(@RequestParam(required = false) String region) {
        return ServiceIncidentsResponse.of(getServiceStatus.handle(new GetServiceStatusQuery(region, null)));
    }
}
