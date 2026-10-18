package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence port for Quality Analyst evaluations. */
public interface QualityEvaluationStore {

    boolean hasAiEvaluation(UUID conversationId);

    void save(QualityAgent.QualityResult result);

    /**
     * Live conversations resolved in {@code [endedAfter, endedBefore)} that have no AI evaluation
     * yet, oldest first, at most {@code limit}: the retry backlog after a failed or rejected grade.
     */
    List<UUID> ungradedResolved(Instant endedAfter, Instant endedBefore, int limit);
}
