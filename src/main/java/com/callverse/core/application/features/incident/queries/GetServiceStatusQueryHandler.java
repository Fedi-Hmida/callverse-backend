package com.callverse.core.application.features.incident.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.ServiceIncidents;
import com.callverse.core.application.interfaces.ServiceIncidents.ActiveIncident;
import java.util.List;
import java.util.Objects;

/**
 * Reports active banking-service outages, optionally for one region ({@code OWNERSHIP_RULES.md}
 * E3).
 *
 * <p><strong>Parked.</strong> No HTTP route calls this: the {@code /internal} route that did was
 * withdrawn on 2026-09-30 pending the AI-integration phase (git tag
 * {@code internal-tools-http-surface}), and roadmap phase FS-1 re-exposes it under {@code /api/v1}.
 *
 * <p>Live by default. Only a caller that names a simulation run sees that run's incidents, so the
 * default can never announce a simulated outage to a real customer. A regional question also
 * returns national outages. An empty list means "no known incident", which should be said plainly
 * rather than guessed at.
 */
public class GetServiceStatusQueryHandler {

    private final ServiceIncidents incidents;

    public GetServiceStatusQueryHandler(ServiceIncidents incidents) {
        this.incidents = Objects.requireNonNull(incidents, "incidents must not be null");
    }

    public List<ActiveIncident> handle(GetServiceStatusQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String region = query.region();
        if (region != null) {
            if (region.isBlank()) {
                // Null means "every region"; a blank string is a caller mistake, not a wildcard.
                throw new InvalidRequestException("region must not be blank");
            }
            region = region.trim();
        }
        return incidents.findActive(region, query.runId());
    }
}
