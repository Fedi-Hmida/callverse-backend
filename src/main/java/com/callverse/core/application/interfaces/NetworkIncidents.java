package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Active network incidents, for the agent's "is there an outage where I live?" tool.
 *
 * <p><strong>Mode scoping is part of the contract.</strong> A simulation run injects incidents with
 * a {@code run_id}. A live question must never see them, and a run must never see live incidents or
 * another run's: the answer to "is there an outage?" is only meaningful inside one universe.
 * {@code OWNERSHIP_RULES.md} E3 records that the pre-existing repository query does not make this
 * distinction; the methods here do.
 */
public interface NetworkIncidents {

    /**
     * @param zone the customer's zone
     * @param runId null for the live system; otherwise the simulation run whose incidents to return
     * @return unresolved incidents in that zone and that universe only, most recent first
     */
    List<ActiveIncident> findActive(String zone, UUID runId);

    record ActiveIncident(
            UUID id,
            String type,
            int severity,
            Instant startedAt,
            Instant estimatedEnd,
            Integer affectedCount) {}
}
