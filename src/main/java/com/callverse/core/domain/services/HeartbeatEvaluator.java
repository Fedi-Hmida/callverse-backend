package com.callverse.core.domain.services;

import com.callverse.core.domain.enums.ServiceStatus;

/**
 * Decides the platform's {@link ServiceStatus} from the capabilities currently available.
 *
 * <p>A pure domain calculator in the sense this package intends: it takes values, returns a value,
 * and reads nothing from the outside. No Spring, no I/O, no clock of its own. That is what makes it
 * testable without a container and what keeps the rule it encodes in one reviewable place.
 *
 * <p>The rule itself is deliberately small for now, but it is a business rule rather than a
 * technical one, and it is the kind that grows: once queue depth, advisor availability and SLA
 * breach counts exist, this is where "the platform is degraded" gets defined.
 */
public final class HeartbeatEvaluator {

    private HeartbeatEvaluator() {
        // Utility holder for a pure function; never instantiated.
    }

    /**
     * @param aiServiceConfigured whether the Python service hosting the agents is configured
     * @return {@link ServiceStatus#UP} when every capability is present, otherwise
     *     {@link ServiceStatus#DEGRADED}, because human advisors can still work without the agents
     */
    public static ServiceStatus evaluate(boolean aiServiceConfigured) {
        return aiServiceConfigured ? ServiceStatus.UP : ServiceStatus.DEGRADED;
    }
}
