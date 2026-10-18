package com.callverse.infrastructure.ai;

import com.callverse.core.application.features.quality.queries.FindUngradedConversationsQueryHandler;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries lost AI grades: every {@code ai.quality.retry.interval} (10 minutes by default), the
 * resolved conversations still without an AI evaluation go back through the normal dispatcher.
 *
 * <p>The dispatcher keeps its one-in-flight guard and the use case its "already graded" check, so a
 * sweep that overlaps the first attempt, or another sweep, does no double work. The window and the
 * batch size are decided in {@link FindUngradedConversationsQueryHandler}. Switch off with
 * {@code ai.quality.retry.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "ai.quality.retry.enabled", havingValue = "true", matchIfMissing = true)
class QualityRetrySweep {

    private static final Logger LOG = System.getLogger(QualityRetrySweep.class.getName());

    private final FindUngradedConversationsQueryHandler backlog;
    private final QualityEvaluationDispatcher dispatcher;

    QualityRetrySweep(FindUngradedConversationsQueryHandler backlog, QualityEvaluationDispatcher dispatcher) {
        this.backlog = backlog;
        this.dispatcher = dispatcher;
    }

    @Scheduled(
            initialDelayString = "${ai.quality.retry.interval:PT10M}",
            fixedDelayString = "${ai.quality.retry.interval:PT10M}")
    void sweep() {
        List<UUID> due;
        try {
            due = backlog.handle();
        } catch (RuntimeException exception) {
            // Never let one failed sweep stop the schedule; the next one tries again.
            LOG.log(Level.WARNING, "Quality retry sweep skipped: {0}", exception.getClass().getSimpleName());
            return;
        }
        if (!due.isEmpty()) {
            LOG.log(Level.INFO, "Quality retry sweep: {0} conversation(s) without an AI grade", due.size());
        }
        due.forEach(dispatcher::dispatch);
    }
}
