package com.callverse.core.application.features.network.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.NetworkIncidents;
import com.callverse.core.application.interfaces.NetworkIncidents.ActiveIncident;
import java.util.List;
import java.util.Objects;

/**
 * Answers the agent tool {@code GET /internal/network/status?zone=} ({@code OWNERSHIP_RULES.md} E3).
 *
 * <p>Live by default. Only a caller that names a simulation run sees that run's incidents, so the
 * default can never announce a simulated outage to a real customer. An empty list means "no known
 * incident", which the agent should say plainly rather than guess.
 */
public class GetNetworkStatusQueryHandler {

    private final NetworkIncidents incidents;

    public GetNetworkStatusQueryHandler(NetworkIncidents incidents) {
        this.incidents = Objects.requireNonNull(incidents, "incidents must not be null");
    }

    public List<ActiveIncident> handle(GetNetworkStatusQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.zone() == null || query.zone().isBlank()) {
            throw new InvalidRequestException("zone must not be blank");
        }
        return incidents.findActive(query.zone().trim(), query.runId());
    }
}
