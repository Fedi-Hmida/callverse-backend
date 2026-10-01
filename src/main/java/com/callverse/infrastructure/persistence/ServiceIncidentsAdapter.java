package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.ServiceIncidents;
import com.callverse.core.domain.entities.ServiceIncident;
import com.callverse.infrastructure.persistence.repositories.ServiceIncidentRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link ServiceIncidents} with Spring Data. The live/run split is separate queries rather
 * than one with an optional parameter, so that "live" can never degrade into "any" through a null.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ServiceIncidentsAdapter implements ServiceIncidents {

    private final ServiceIncidentRepository repository;

    @Override
    public List<ActiveIncident> findActive(String region, UUID runId) {
        List<ServiceIncident> rows;
        if (runId == null) {
            rows = region == null
                    ? repository.findActiveLive()
                    : repository.findActiveLiveInRegion(region);
        } else {
            rows = region == null
                    ? repository.findActiveInRun(runId)
                    : repository.findActiveInRunInRegion(runId, region);
        }
        return rows.stream()
                .map(i -> new ActiveIncident(
                        i.getId(),
                        i.getService(),
                        i.getRegion(),
                        i.getSeverity(),
                        i.getDescription(),
                        i.getStartedAt(),
                        i.getEstimatedEnd(),
                        i.getAffectedCount()))
                .toList();
    }
}
