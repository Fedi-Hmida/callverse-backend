package com.callverse.core.application.interfaces;

import java.util.UUID;

/** Schedules a quality evaluation without blocking the HTTP request. */
public interface QualityEvaluationQueue {
    void enqueue(UUID conversationId);
}
