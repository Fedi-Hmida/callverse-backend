package com.callverse.host.api.controllers;

import com.callverse.core.application.features.health.queries.GetHealthStatusQuery;
import com.callverse.core.application.features.health.queries.GetHealthStatusQueryHandler;
import com.callverse.host.api.dto.response.HealthStatusApiMapper;
import com.callverse.host.api.dto.response.HealthStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Walking skeleton: the thinnest possible request that still crosses every layer.
 *
 * <p>HTTP arrives here, the controller builds a query, an application handler answers it using a
 * domain calculator, and a mapper turns the result into the published response shape. The
 * controller itself decides nothing; that is the property worth copying, not the endpoint.
 *
 * <p>Temporary by design. This slice is deleted once real features land, and deleting it means
 * removing one directory per layer.
 *
 * <p>Note this is not {@code /actuator/health}. Actuator answers "is this process alive" for the
 * deployment platform; this answers "can the relation center do its job" for the product.
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@Tag(name = "Health", description = "Platform status (walking skeleton)")
public class HealthController {

    private final GetHealthStatusQueryHandler getHealthStatus;
    private final HealthStatusApiMapper mapper;

    @GetMapping("/status")
    @Operation(
            summary = "Read platform status",
            description =
                    "Returns the service name, build version, business-level status, UTC timestamp "
                            + "and active profile.")
    public HealthStatusResponse status() {
        return mapper.toResponse(getHealthStatus.handle(new GetHealthStatusQuery()));
    }
}
