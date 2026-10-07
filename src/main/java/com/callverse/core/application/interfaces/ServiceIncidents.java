package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.BankingService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Active banking-service outages, for the "is it me or is it the bank?" question.
 *
 * <p><strong>Mode scoping is part of the contract.</strong> A simulation run injects incidents with
 * a {@code run_id}. A live question must never see them, and a run must never see live incidents or
 * another run's: the answer to "is there an outage?" is only meaningful inside one universe
 * ({@code OWNERSHIP_RULES.md} E3).
 */
public interface ServiceIncidents {

    /**
     * @param region the customer's region, or null for every region
     * @param runId null for the live system; otherwise the simulation run whose incidents to return
     * @return unresolved incidents in that region (national ones included) and that universe only,
     *     most recent first
     */
    List<ActiveIncident> findActive(String region, UUID runId);

    record ActiveIncident(
            UUID id,
            BankingService service,
            String region,
            int severity,
            String description,
            Instant startedAt,
            Instant estimatedEnd,
            Integer affectedCount) {}
}
