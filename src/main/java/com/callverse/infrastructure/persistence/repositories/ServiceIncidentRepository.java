package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.ServiceIncident;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Operational. Distinguishes a known outage from a problem with one customer's account.
 *
 * <p>Four queries rather than one with optional parameters: "live" must never degrade into "any"
 * through a null run id, and a null region parameter is not something PostgreSQL can type. Every
 * query returns national incidents ({@code region IS NULL}) alongside regional ones, because a
 * national outage affects every region. {@code idx_service_incident_active} is partial on
 * {@code resolved_at IS NULL}.
 */
@Repository
public interface ServiceIncidentRepository extends JpaRepository<ServiceIncident, UUID> {

    /** Every unresolved live incident. */
    @Query("""
           select i from ServiceIncident i
            where i.resolvedAt is null and i.runId is null
            order by i.startedAt desc
           """)
    List<ServiceIncident> findActiveLive();

    /** Unresolved live incidents in one region, plus the national ones. */
    @Query("""
           select i from ServiceIncident i
            where i.resolvedAt is null and i.runId is null
              and (i.region = :region or i.region is null)
            order by i.startedAt desc
           """)
    List<ServiceIncident> findActiveLiveInRegion(@Param("region") String region);

    /** One simulation run's unresolved incidents, and nothing from the live system or other runs. */
    @Query("""
           select i from ServiceIncident i
            where i.resolvedAt is null and i.runId = :runId
            order by i.startedAt desc
           """)
    List<ServiceIncident> findActiveInRun(@Param("runId") UUID runId);

    /** One run's unresolved incidents in one region, plus that run's national ones. */
    @Query("""
           select i from ServiceIncident i
            where i.resolvedAt is null and i.runId = :runId
              and (i.region = :region or i.region is null)
            order by i.startedAt desc
           """)
    List<ServiceIncident> findActiveInRunInRegion(
            @Param("runId") UUID runId, @Param("region") String region);
}
