package com.callverse.core.application.interfaces;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One metric reading, as the application layer hands it to {@link MetricSampleBatchWriter}.
 *
 * <p>A plain record rather than the {@code MetricSample} entity, deliberately. The entity is a JPA
 * type; passing it through this port would put {@code jakarta.persistence} into the application
 * layer's vocabulary and drag the persistence context into a path whose entire purpose is to avoid
 * it. This record carries the seven column values and nothing else.
 *
 * @param runId the simulation run this reading belongs to
 * @param simTime seconds since the run started, sampled every 10s
 * @param skillId the skill whose queue was read; never null, because the database's primary key
 *     forbids it even though the column is declared as a nullable foreign key
 * @param queueLength conversations waiting for this skill at this instant
 * @param avgWait mean wait in seconds of those waiting, NUMERIC(8,2)
 * @param availableCount advisors idle and able to take work
 * @param busyCount advisors currently handling at least one conversation
 */
public record MetricSampleRecord(
        UUID runId,
        int simTime,
        UUID skillId,
        Integer queueLength,
        BigDecimal avgWait,
        Integer availableCount,
        Integer busyCount) {}
