package com.callverse.infrastructure.ai;

import com.callverse.core.application.features.conversation.commands.AdvisorTurnCommand;
import com.callverse.core.application.features.conversation.commands.AdvisorTurnCommandHandler;
import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.domain.enums.MessageSender;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/** Submits customer-message Advisor turns to a bounded worker pool, one in flight per conversation. */
@Component
class AdvisorTurnDispatcher {

    private static final Logger LOG = System.getLogger(AdvisorTurnDispatcher.class.getName());

    private final AdvisorTurnCommandHandler handler;
    private final TaskExecutor executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    AdvisorTurnDispatcher(
            AdvisorTurnCommandHandler handler, @Qualifier("advisorTurnExecutor") TaskExecutor advisorTurnExecutor) {
        this.handler = handler;
        this.executor = advisorTurnExecutor;
    }

    void dispatch(ConversationEvent event) {
        if (event.type() != ConversationEvent.Type.MESSAGE_POSTED
                || event.sender() != MessageSender.CUSTOMER
                || event.messageId() == null) {
            return;
        }
        if (!inFlight.add(event.conversationId())) {
            LOG.log(Level.INFO, "AI Advisor turn ignored: one is already in flight for conversation {0}",
                    event.conversationId());
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    handler.handle(new AdvisorTurnCommand(event.conversationId(), event.messageId()));
                } catch (RuntimeException exception) {
                    LOG.log(Level.WARNING, "AI Advisor worker failed for conversation {0}",
                            event.conversationId());
                } finally {
                    inFlight.remove(event.conversationId());
                }
            });
        } catch (TaskRejectedException exception) {
            inFlight.remove(event.conversationId());
            LOG.log(Level.WARNING, "AI Advisor worker rejected for conversation {0}", event.conversationId());
        }
    }
}
