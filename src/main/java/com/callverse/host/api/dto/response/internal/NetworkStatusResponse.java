package com.callverse.host.api.dto.response.internal;

import com.callverse.core.application.interfaces.NetworkIncidents.ActiveIncident;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tool response for {@code GET /internal/network/status}. <strong>Provisional.</strong>
 * {@code activeIncident} is there so the agent can branch without inspecting the list.
 */
@Schema(description = "Active network incidents in a zone, live unless a run is named. Provisional.")
public record NetworkStatusResponse(
        @Schema(example = "MARSEILLE-8") String zone,
        @Schema(nullable = true, description = "Echoes the runId asked about; null for the live system") UUID runId,
        @Schema(example = "true") boolean activeIncident,
        @Schema(description = "Most recent first; empty when no incident is known") List<IncidentResponse> incidents) {

    public record IncidentResponse(
            UUID id,
            @Schema(example = "FIBER_CUT") String type,
            @Schema(example = "4", description = "1 (minor) to 5 (critical)") int severity,
            Instant startedAt,
            @Schema(nullable = true) Instant estimatedEnd,
            @Schema(nullable = true, example = "1200") Integer affectedCount) {}

    public static NetworkStatusResponse from(String zone, UUID runId, List<ActiveIncident> incidents) {
        return new NetworkStatusResponse(
                zone,
                runId,
                !incidents.isEmpty(),
                incidents.stream()
                        .map(i -> new IncidentResponse(
                                i.id(), i.type(), i.severity(), i.startedAt(), i.estimatedEnd(), i.affectedCount()))
                        .toList());
    }
}
