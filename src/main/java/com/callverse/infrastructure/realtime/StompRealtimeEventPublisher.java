package com.callverse.infrastructure.realtime;

import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.SupervisionAlert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Backs {@link RealtimeEventPublisher} with the STOMP broker.
 *
 * <p><strong>After commit, or not at all.</strong> When a transaction is active on the calling
 * thread, the send is registered to run after it commits and is simply never run on rollback.
 * Outside a transaction — the normal case, since adapters commit before the use case continues —
 * the change is already durable and the send happens at once. Either way, no client hears about a
 * change the database does not hold.
 *
 * <p>Topic names live here and nowhere in {@code core}: they are part of the delivery contract
 * (brief 01, the five frozen topics), not of the business.
 */
@Component
@Slf4j
public class StompRealtimeEventPublisher implements RealtimeEventPublisher {

    static final String SUPERVISION_ALERTS = "/topic/supervision/alerts";

    private final SimpMessageSendingOperations messaging;

    public StompRealtimeEventPublisher(SimpMessageSendingOperations messaging) {
        this.messaging = messaging;
    }

    @Override
    public void publishSupervisionAlert(SupervisionAlert alert) {
        afterCommit(() -> messaging.convertAndSend(SUPERVISION_ALERTS, alert));
    }

    private static void afterCommit(Runnable send) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safely(send);
                }
            });
        } else {
            safely(send);
        }
    }

    /**
     * A live notification is a courtesy on top of a change that is already committed: a broker
     * failure must not turn a successful card block or escalation into an error for the advisor.
     */
    private static void safely(Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            log.warn("Realtime event not delivered: {}", e.getMessage());
        }
    }
}
