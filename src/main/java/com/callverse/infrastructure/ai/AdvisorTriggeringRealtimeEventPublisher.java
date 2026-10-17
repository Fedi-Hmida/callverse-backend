package com.callverse.infrastructure.ai;

import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import com.callverse.core.application.interfaces.QueueEvent;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.SupervisionAlert;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decorates the realtime publisher to trigger the Advisor only after a customer message commits.
 *
 * <p>The existing STOMP publisher remains responsible for delivery. This adapter adds no state to
 * the event and does not trigger on AI or human advisor messages.
 */
@Component
@Primary
class AdvisorTriggeringRealtimeEventPublisher implements RealtimeEventPublisher {

    private final RealtimeEventPublisher delegate;
    private final ObjectProvider<AdvisorTurnDispatcher> dispatcherProvider;

    AdvisorTriggeringRealtimeEventPublisher(
            @Qualifier("stompRealtimeEventPublisher") RealtimeEventPublisher delegate,
            ObjectProvider<AdvisorTurnDispatcher> dispatcherProvider) {
        this.delegate = delegate;
        this.dispatcherProvider = dispatcherProvider;
    }

    @Override
    public void publishSupervisionAlert(SupervisionAlert alert) {
        delegate.publishSupervisionAlert(alert);
    }

    @Override
    public void publishQueueEvent(QueueEvent event) {
        delegate.publishQueueEvent(event);
    }

    @Override
    public void publishConversationEvent(ConversationEvent event) {
        delegate.publishConversationEvent(event);
        if (event.type() == ConversationEvent.Type.MESSAGE_POSTED
                && event.sender() == com.callverse.core.domain.enums.MessageSender.CUSTOMER) {
            afterCommit(() -> dispatcherProvider.getObject().dispatch(event));
        }
    }

    @Override
    public void publishLiveKpi(LiveKpiSnapshot snapshot) {
        delegate.publishLiveKpi(snapshot);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
