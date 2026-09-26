package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.NetworkIncident;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Operational. Queried by zone during technical triage to distinguish a known outage from a
 * fault on one customer's line.
 */
@Repository
public interface NetworkIncidentRepository extends JpaRepository<NetworkIncident, UUID> {

    /** Matches idx_incident_zone_active, which is partial on resolved_at IS NULL. */
    List<NetworkIncident> findByZoneAndResolvedAtIsNull(String zone);

    List<NetworkIncident> findByResolvedAtIsNull();

    /**
     * Live active incidents in a zone: {@code run_id IS NULL}, so no simulation run's injected
     * outage can reach a real customer ({@code OWNERSHIP_RULES.md} E3). The zone-and-active part is
     * served by the partial index {@code idx_incident_zone_active}; the run filter is applied to the
     * few rows it returns.
     */
    List<NetworkIncident> findByZoneAndResolvedAtIsNullAndRunIdIsNullOrderByStartedAtDesc(String zone);

    /** One simulation run's active incidents in a zone, and nothing from the live system or other runs. */
    List<NetworkIncident> findByZoneAndResolvedAtIsNullAndRunIdOrderByStartedAtDesc(String zone, UUID runId);
}
