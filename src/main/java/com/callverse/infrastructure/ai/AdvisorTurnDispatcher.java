package com.callverse.infrastructure.ai;

import com.callverse.core.application.features.conversation.commands.AdvisorTurnCommand;
import com.callverse.core.application.features.conversation.commands.AdvisorTurnCommandHandler;
import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.domain.enums.MessageSender;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Submits customer-message Advisor turns to a bounded worker pool, one in flight per conversation.
 *
 * <p><strong>Coalescing, not dropping.</strong> A customer message that arrives while a turn is in
 * flight is recorded as the conversation's pending message, and a new turn is queued once the
 * current one ends. Pending messages collapse to the latest one (message ids only grow), so the AI
 * answers what the customer said last, with the earlier messages in its history. The in-flight
 * turn's own answer is discarded under lock because it is no longer the latest message.
 *
 * <p><strong>Fair and without lost wake-ups.</strong> Each task answers one message and then
 * releases the conversation, re-checking for a pending message after the release, so a message that
 * lands in between is never stranded and a busy conversation cannot hold a worker.
 */
@Component
class AdvisorTurnDispatcher {

    private static final Logger LOG = System.getLogger(AdvisorTurnDispatcher.class.getName());

    private final AdvisorTurnCommandHandler handler;
    private final TaskExecutor executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();

    AdvisorTurnDispatcher(
            AdvisorTurnCommandHandler handler, @Qualifier("advisorTurnExecutor") TaskExecutor advisorTurnExecutor) {
        this.handler = handler;
        this.executor = advisorTurnExecutor;
    }

    void dispatch(ConversationEvent event) {
        if (event.type() != ConversationEvent.Type.MESSAGE_POSTED
                || event.sender() != MessageSender.CUSTOMER
                || event.messageId() == null
                || !handler.enabled()) {
            return;
        }
        pending.merge(event.conversationId(), event.messageId(), Math::max);
        schedule(event.conversationId());
    }

    private void schedule(UUID conversationId) {
        if (!inFlight.add(conversationId)) {
            return; // the running turn reschedules itself when it ends
        }
        try {
            executor.execute(() -> runOneTurn(conversationId));
        } catch (RejectedExecutionException exception) {
            // Saturated. The message stays pending: the conversation's next event schedules it.
            inFlight.remove(conversationId);
            LOG.log(Level.WARNING, "AI Advisor worker rejected for conversation {0}", conversationId);
        }
    }

    /**
     * One turn per task, so a conversation that keeps writing goes back to the end of the queue
     * instead of holding a worker while other conversations wait.
     */
    private void runOneTurn(UUID conversationId) {
        try {
            Long messageId = pending.remove(conversationId);
            if (messageId != null) {
                handler.handle(new AdvisorTurnCommand(conversationId, messageId));
            }
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "AI Advisor worker failed for conversation {0}", conversationId);
        } finally {
            inFlight.remove(conversationId);
            if (pending.containsKey(conversationId)) {
                schedule(conversationId);
            }
        }
    }
}
