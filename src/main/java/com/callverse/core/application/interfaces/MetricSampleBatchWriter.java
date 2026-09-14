package com.callverse.core.application.interfaces;

import java.util.List;

/**
 * Port for writing simulation metrics in bulk.
 *
 * <p><strong>Why this port exists instead of a repository.</strong> {@code metric_sample} is the
 * high-volume table of the experiment universe: roughly 650,000 rows across the full experimental
 * matrix, arriving in bursts while a run executes. Writing those through JPA would issue one INSERT
 * per row, accumulate every row as a managed entity in the persistence context, and pay a network
 * round trip each time — against Neon, where storage is separated from compute, that is the
 * difference between seconds and many minutes.
 *
 * <p>So the write path deliberately bypasses JPA. The implementation, which arrives in a later
 * phase, will use {@code JdbcTemplate.batchUpdate} in lots of 500. The read path keeps a normal
 * (read-only) JPA entity, because reading a run's samples back to plot it is an ordinary query.
 *
 * <p>The guard against someone reintroducing the slow path is structural: {@code MetricSample} is
 * {@code @Immutable} and {@code MetricSampleRepository} extends Spring Data's bare
 * {@code Repository} rather than {@code JpaRepository}, so {@code saveAll} does not exist on the
 * type. This interface is where writes are supposed to go, and it is the only place they can.
 *
 * <p>Declared in {@code core.application.interfaces} and referencing no Spring, JDBC or JPA type:
 * the application layer states what it needs, and {@code infrastructure} decides how.
 */
public interface MetricSampleBatchWriter {

    /**
     * Writes a batch of readings.
     *
     * <p>Callers should hand over lots of roughly 500 rather than one reading at a time; the whole
     * point of this port is that the batch boundary is the caller's decision and the transport is
     * not.
     *
     * @param samples readings to persist; an empty list is a no-op rather than an error, so that a
     *     run which produced no samples for an interval needs no special case at the call site
     * @return the number of rows written
     */
    int writeBatch(List<MetricSampleRecord> samples);
}
