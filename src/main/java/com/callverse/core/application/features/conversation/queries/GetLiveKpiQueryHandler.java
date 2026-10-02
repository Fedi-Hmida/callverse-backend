package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import com.callverse.core.application.interfaces.LiveOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Today's live supervision banner, in the same shape the KPI topic pushes, so a screen loads it once
 * and then follows the topic. Live conversations only (rule C6).
 */
public class GetLiveKpiQueryHandler {

    private final LiveOperations operations;
    private final ConversationEvents events;
    private final Clock clock;

    public GetLiveKpiQueryHandler(LiveOperations operations, ConversationEvents events, Clock clock) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public LiveKpiSnapshot handle() {
        Instant now = clock.instant();
        return operations.snapshot(events.startOfDay(now), now);
    }
}
