package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.NetworkIncidents;
import com.callverse.core.domain.entities.NetworkIncident;
import com.callverse.infrastructure.persistence.repositories.NetworkIncidentRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link NetworkIncidents} with Spring Data. The live/run split is two separate queries rather
 * than one with an optional parameter, so that "live" can never degrade into "any" through a null.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class NetworkIncidentsAdapter implements NetworkIncidents {

    private final NetworkIncidentRepository repository;

    @Override
    public List<ActiveIncident> findActive(String zone, UUID runId) {
        List<NetworkIncident> rows =
                runId == null
                        ? repository.findByZoneAndResolvedAtIsNullAndRunIdIsNullOrderByStartedAtDesc(zone)
                        : repository.findByZoneAndResolvedAtIsNullAndRunIdOrderByStartedAtDesc(zone, runId);
        return rows.stream()
                .map(i -> new ActiveIncident(
                        i.getId(),
                        i.getType(),
                        i.getSeverity(),
                        i.getStartedAt(),
                        i.getEstimatedEnd(),
                        i.getAffectedCount()))
                .toList();
    }
}
