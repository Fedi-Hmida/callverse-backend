package com.callverse.core.application.features.network.queries;

import java.util.UUID;

/**
 * @param zone the zone to check
 * @param runId null for the live system; a simulation run's id to ask inside that run
 */
public record GetNetworkStatusQuery(String zone, UUID runId) {}
