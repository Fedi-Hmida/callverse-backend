package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.List;

/**
 * Live operating figures, read from the denormalized columns written at each transition — never
 * recomputed from timestamps on read. Live conversations only ({@code run_id IS NULL}, rule C6).
 */
public interface LiveOperations {

    /** Every skill, with how many conversations wait in it now and for how long the oldest has. */
    List<QueueDepth> queueDepths(Instant now);

    /** The supervision banner: queues now, plus what happened since {@code since}. */
    LiveKpiSnapshot snapshot(Instant since, Instant now);

    /**
     * @param oldestWaitSeconds how long the oldest waiting conversation has waited; null when empty
     */
    record QueueDepth(String skill, long waiting, Integer oldestWaitSeconds) {}
}
