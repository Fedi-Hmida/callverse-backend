package com.callverse.infrastructure.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.callverse.core.application.features.quality.queries.FindUngradedConversationsQueryHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Each sweep hands the ungraded backlog to the existing dispatcher, which stays the only way in. */
class QualityRetrySweepTest {

    private final FindUngradedConversationsQueryHandler backlog = mock(FindUngradedConversationsQueryHandler.class);
    private final QualityEvaluationDispatcher dispatcher = mock(QualityEvaluationDispatcher.class);

    @Test
    void dispatchesEveryUngradedConversation() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(backlog.handle()).thenReturn(List.of(first, second));

        new QualityRetrySweep(backlog, dispatcher).sweep();

        verify(dispatcher).dispatch(first);
        verify(dispatcher).dispatch(second);
    }

    @Test
    void anEmptyBacklogDispatchesNothing() {
        when(backlog.handle()).thenReturn(List.of());

        new QualityRetrySweep(backlog, dispatcher).sweep();

        verifyNoInteractions(dispatcher);
    }

    @Test
    void aFailingBacklogQueryDoesNotKillTheSchedule() {
        when(backlog.handle()).thenThrow(new IllegalStateException("database down"));

        new QualityRetrySweep(backlog, dispatcher).sweep();

        verifyNoInteractions(dispatcher);
    }
}
