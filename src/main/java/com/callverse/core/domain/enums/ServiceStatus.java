package com.callverse.core.domain.enums;

/**
 * Operational state of the CallVerse platform as a whole.
 *
 * <p>Distinct from a Spring Boot actuator health status: this is a business-level statement about
 * whether the relation center can actually do its job. The platform can be running perfectly as a
 * web application while being unable to serve customers, and that case is {@link #DEGRADED}.
 */
public enum ServiceStatus {

    /** Every capability the platform needs is available. */
    UP,

    /**
     * The platform is serving requests but at least one capability is missing. The common case is
     * that the Python AI service is unreachable or unconfigured: conversations can still be queued
     * and handled by human advisors, but the autonomous agents cannot run.
     */
    DEGRADED,

    /** The platform cannot serve requests. */
    DOWN
}
