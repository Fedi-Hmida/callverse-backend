package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.MetricSample;
import com.callverse.core.domain.entities.MetricSampleId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Read-only access to simulation metrics.
 *
 * <p><strong>This extends Spring Data's bare {@code Repository}, not {@code JpaRepository}, and that
 * is the entire point.</strong> {@code JpaRepository} would inherit {@code save} and
 * {@code saveAll}, and {@code saveAll} on this table is a defect rather than a shortcut: roughly
 * 650,000 rows across the experimental matrix, written one INSERT at a time through the persistence
 * context, each paying a round trip to Neon where storage is separated from compute.
 *
 * <p>By extending the marker interface instead, only the methods declared below exist. There is no
 * {@code save} to reach for, so the mistake is a compile error rather than a code-review catch that
 * someone eventually misses. Writes go through
 * {@link com.callverse.core.application.interfaces.MetricSampleBatchWriter}, which batches through
 * JDBC.
 *
 * <p>The corresponding entity is {@code @Immutable} for the same reason.
 */
@org.springframework.stereotype.Repository
public interface MetricSampleRepository extends Repository<MetricSample, MetricSampleId> {

    /** A run's full time series for one skill, ready to plot. */
    List<MetricSample> findByIdRunIdAndIdSkillIdOrderByIdSimTimeAsc(UUID runId, UUID skillId);

    /** A run's full time series across every skill. */
    List<MetricSample> findByIdRunIdOrderByIdSimTimeAsc(UUID runId);

    /** Cheap sanity check that a run actually produced samples, without loading them. */
    long countByIdRunId(UUID runId);
}
