package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.ServiceIncidents.ActiveIncident;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Active live outages. The simulation run id is deliberately not part of this shape: the public
 * route only ever answers about the live system.
 */
@Schema(name = "ServiceIncidentsResponse", description = "Active outages of banking services")
public record ServiceIncidentsResponse(@Schema(requiredMode = REQUIRED) List<Incident> incidents) {

    @Schema(name = "ServiceIncident", description = "One active outage")
    public record Incident(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED, example = "CARD_PAYMENTS",
                    allowableValues = {"CARD_PAYMENTS", "ONLINE_BANKING", "MOBILE_APP", "ATM_NETWORK", "TRANSFERS"})
                    String service,
            @Schema(nullable = true, description = "Null for a national outage", example = "Marseille") String region,
            @Schema(requiredMode = REQUIRED, description = "1 (worst) to 5", example = "2") int severity,
            @Schema(nullable = true, example = "Card authorisations failing") String description,
            @Schema(requiredMode = REQUIRED) Instant startedAt,
            @Schema(nullable = true, description = "What the customer can be told") Instant estimatedEnd,
            @Schema(nullable = true) Integer affectedCount) {}

    public static ServiceIncidentsResponse of(List<ActiveIncident> incidents) {
        return new ServiceIncidentsResponse(incidents.stream()
                .map(i -> new Incident(
                        i.id(),
                        i.service().name(),
                        i.region(),
                        i.severity(),
                        i.description(),
                        i.startedAt(),
                        i.estimatedEnd(),
                        i.affectedCount()))
                .toList());
    }
}
