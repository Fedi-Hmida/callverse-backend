package com.callverse.core.application.interfaces;

/**
 * Port giving the application layer the few facts about its own deployment that a use case may
 * legitimately need.
 *
 * <p>This is the pattern every port in this package follows, and the reason the dependency rule
 * holds: the use case declares <em>what</em> it needs, and something in {@code infrastructure}
 * decides <em>where</em> that comes from. The handler never learns that these values happen to
 * arrive from Spring's {@code Environment} and a Maven-filtered property, so the application layer
 * has no reason to import Spring at all.
 */
public interface PlatformMetadataProvider {

    /** Human-readable name of this service, as it should appear to clients. */
    String serviceName();

    /** Deployed version of this service, for correlating a client report with a build. */
    String version();

    /** The profile the service is running under, such as {@code dev}. */
    String activeProfile();

    /**
     * Whether the Python service hosting the LangGraph agents and the RL policy is configured.
     * Feeds the domain rule in {@code HeartbeatEvaluator}.
     */
    boolean aiServiceConfigured();
}
