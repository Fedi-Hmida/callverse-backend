package com.callverse.core.application.interfaces;

import java.util.UUID;

/** Persistence port for Quality Analyst evaluations. */
public interface QualityEvaluationStore {

    boolean hasAiEvaluation(UUID conversationId);

    void save(QualityAgent.QualityResult result);
}
