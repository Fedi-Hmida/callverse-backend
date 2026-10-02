package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.exceptions.AdvisorProfileNotFoundException;
import com.callverse.core.application.interfaces.AdvisorDirectory;
import com.callverse.core.application.interfaces.AdvisorDirectory.AdvisorProfile;
import com.callverse.core.application.interfaces.LiveOperations;
import com.callverse.core.application.interfaces.LiveOperations.QueueDepth;
import com.callverse.core.domain.enums.UserRole;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * How many conversations wait in each skill queue, and for how long the oldest has. An advisor sees
 * only the queues of skills they hold (rule B6); supervisors and administrators see every queue.
 */
public class GetQueuesQueryHandler {

    private final AdvisorDirectory advisors;
    private final LiveOperations operations;
    private final Clock clock;

    public GetQueuesQueryHandler(AdvisorDirectory advisors, LiveOperations operations, Clock clock) {
        this.advisors = Objects.requireNonNull(advisors, "advisors must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public List<QueueDepth> handle(GetQueuesQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.caller().role() != UserRole.ADVISOR) {
            return operations.queueDepths(clock.instant());
        }
        AdvisorProfile advisor = advisors.findByUserId(query.caller().userId())
                .orElseThrow(() -> new AdvisorProfileNotFoundException(query.caller().userId()));
        return operations.queueDepths(clock.instant()).stream().filter(depth -> advisor.holds(depth.skill())).toList();
    }
}
