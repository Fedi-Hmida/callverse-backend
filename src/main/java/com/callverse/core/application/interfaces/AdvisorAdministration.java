package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.AdvisorStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Explicit, administrator-controlled staffing capabilities; never grants commercial-credit authority. */
public interface AdvisorAdministration {
    Profile profile(UUID actorId, UUID userId);
    Profile configure(UUID actorId, UUID userId, Configuration configuration);

    record Configuration(AdvisorStatus status, int maxConcurrent, Map<String, Integer> skills) {}
    record Profile(UUID id, UUID userId, String displayName, AdvisorStatus status,
                   int maxConcurrent, BigDecimal creditLimit, Map<String, Integer> skills) {}
}
