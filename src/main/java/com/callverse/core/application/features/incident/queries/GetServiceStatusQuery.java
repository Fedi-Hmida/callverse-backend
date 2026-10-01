package com.callverse.core.application.features.incident.queries;

import java.util.UUID;

/**
 * @param region the region to check, or null for every region
 * @param runId null for the live system; a simulation run's id to ask inside that run
 */
public record GetServiceStatusQuery(String region, UUID runId) {}
