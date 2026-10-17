package com.callverse.infrastructure.ai;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.domain.enums.ConversationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AdvisorTriggeringRealtimeEventPublisherTest {

    @Mock
    private RealtimeEventPublisher delegate;

    @Mock
    private QualityEvaluationDispatcher qualityDispatcher;

    @Mock
    private ObjectProvider<AdvisorTurnDispatcher> advisorProvider;

    @Mock
    private ObjectProvider<QualityEvaluationDispatcher> qualityProvider;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void schedulesQualityEvaluationOnlyAfterResolvedStatusCommits() {
        UUID conversationId = UUID.randomUUID();
        when(qualityProvider.getObject()).thenReturn(qualityDispatcher);
        AdvisorTriggeringRealtimeEventPublisher publisher =
                new AdvisorTriggeringRealtimeEventPublisher(delegate, advisorProvider, qualityProvider);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishConversationEvent(ConversationEvent.statusChanged(
                conversationId, ConversationStatus.RESOLVED, Instant.EPOCH));

        verify(qualityProvider, never()).getObject();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(qualityDispatcher).dispatch(conversationId);
    }

    @Test
    void doesNotScheduleQualityEvaluationForNonResolvedStatus() {
        AdvisorTriggeringRealtimeEventPublisher publisher =
                new AdvisorTriggeringRealtimeEventPublisher(delegate, advisorProvider, qualityProvider);
        publisher.publishConversationEvent(
                ConversationEvent.statusChanged(UUID.randomUUID(), ConversationStatus.ESCALATED, Instant.EPOCH));

        verify(qualityProvider, never()).getObject();
    }
}
