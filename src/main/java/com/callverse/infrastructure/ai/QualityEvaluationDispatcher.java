package com.callverse.infrastructure.ai;

import com.callverse.core.application.features.quality.commands.QualityEvaluationCommandHandler;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/** Submits resolved conversation evaluations to a bounded worker pool. */
@Component
class QualityEvaluationDispatcher {

    private static final Logger LOG = System.getLogger(QualityEvaluationDispatcher.class.getName());

    private final QualityEvaluationCommandHandler handler;
    private final TaskExecutor executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    QualityEvaluationDispatcher(
            QualityEvaluationCommandHandler handler, @Qualifier("qualityEvaluationExecutor") TaskExecutor executor) {
        this.handler = handler;
        this.executor = executor;
    }

    void dispatch(UUID conversationId) {
        if (!inFlight.add(conversationId)) {
            LOG.log(Level.INFO, "Quality evaluation ignored: one is already in flight for conversation {0}",
                    conversationId);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    if (!handler.handle(conversationId)) {
                        LOG.log(Level.INFO, "Quality evaluation skipped for conversation {0}", conversationId);
                    }
                } catch (RuntimeException exception) {
                    LOG.log(Level.WARNING, "Quality evaluation failed for conversation {0}", conversationId);
                } finally {
                    inFlight.remove(conversationId);
                }
            });
        } catch (TaskRejectedException exception) {
            inFlight.remove(conversationId);
            LOG.log(Level.WARNING, "Quality evaluation worker rejected for conversation {0}", conversationId);
        }
    }
}
