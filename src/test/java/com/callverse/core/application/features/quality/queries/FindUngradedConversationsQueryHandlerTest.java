package com.callverse.core.application.features.quality.queries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.callverse.core.application.interfaces.QualityEvaluationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which resolved conversations get another chance at an AI grade. */
class FindUngradedConversationsQueryHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    @Test
    void asksForUngradedConversationsEndedBetweenFiveMinutesAndADayAgoInBatchesOfTwenty() {
        QualityEvaluationStore store = mock(QualityEvaluationStore.class);
        UUID due = UUID.randomUUID();
        // Five minutes leave the first attempt time to finish; after a day the grade is abandoned.
        when(store.ungradedResolved(NOW.minus(Duration.ofHours(24)), NOW.minus(Duration.ofMinutes(5)), 20))
                .thenReturn(List.of(due));

        List<UUID> found = new FindUngradedConversationsQueryHandler(store, Clock.fixed(NOW, ZoneOffset.UTC)).handle();

        assertThat(found).containsExactly(due);
    }
}
