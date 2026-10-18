package com.callverse.core.application.features.quality.queries;

import com.callverse.core.application.interfaces.QualityEvaluationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Finds resolved conversations that should get another attempt at an AI grade.
 *
 * <p>A grade is lost when the judge is down or rejects an incomplete answer; one attempt per
 * resolution is not enough. The window starts {@link #SETTLE} after resolution, so the first,
 * immediate attempt has time to finish, and ends after {@link #GIVE_UP}, so a conversation the judge
 * keeps failing on is not retried forever. {@link #BATCH} bounds each sweep.
 */
public class FindUngradedConversationsQueryHandler {

    static final Duration SETTLE = Duration.ofMinutes(5);
    static final Duration GIVE_UP = Duration.ofHours(24);
    static final int BATCH = 20;

    private final QualityEvaluationStore store;
    private final Clock clock;

    public FindUngradedConversationsQueryHandler(QualityEvaluationStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public List<UUID> handle() {
        Instant now = clock.instant();
        return store.ungradedResolved(now.minus(GIVE_UP), now.minus(SETTLE), BATCH);
    }
}
