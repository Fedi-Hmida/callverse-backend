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
}
