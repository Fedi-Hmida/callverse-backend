package com.callverse.core.application.features.workforce.commands;

import com.callverse.core.application.exceptions.WorkforceServiceUnavailableException;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.LiveOperations;
import com.callverse.core.application.interfaces.WorkforceAgent;
import com.callverse.core.application.interfaces.WorkforceAgent.WorkforceProposal;
import com.callverse.core.application.interfaces.WorkforceAgent.WorkforceState;
import com.callverse.core.application.interfaces.WorkforceDecisionLog;
import com.callverse.core.application.interfaces.WorkforceOperations;
import com.callverse.core.application.interfaces.WorkforceOperations.WorkforceInputs;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/** Requests and records a live Workforce recommendation for a supervisor to review. */
public class RequestWorkforceRecommendationCommandHandler {

    private static final System.Logger LOG =
            System.getLogger(RequestWorkforceRecommendationCommandHandler.class.getName());
    private static final double NEUTRAL_SLA = 1.0;
    private static final double NEUTRAL_TREND = 1.0;

    private final WorkforceOperations workforceOperations;
    private final LiveOperations liveOperations;
    private final ConversationEvents events;
    private final WorkforceAgent agent;
    private final WorkforceDecisionLog decisionLog;
    private final Clock clock;
    private final ZoneId operationsZone;

    public RequestWorkforceRecommendationCommandHandler(
            WorkforceOperations workforceOperations,
            LiveOperations liveOperations,
            ConversationEvents events,
            WorkforceAgent agent,
            WorkforceDecisionLog decisionLog,
            Clock clock,
            ZoneId operationsZone) {
        this.workforceOperations =
                Objects.requireNonNull(workforceOperations, "workforceOperations must not be null");
        this.liveOperations = Objects.requireNonNull(liveOperations, "liveOperations must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.decisionLog = Objects.requireNonNull(decisionLog, "decisionLog must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.operationsZone = Objects.requireNonNull(operationsZone, "operationsZone must not be null");
    }

    public Recommendation handle() {
        Instant now = clock.instant();
        WorkforceInputs inputs = workforceOperations.inputs(now);
        Double slaRatio = liveOperations.snapshot(events.startOfDay(now), now).slaRatio();
        WorkforceState observation = new WorkforceState(
                inputs.queues(),
                inputs.averageWaitSeconds(),
                inputs.available(),
                slaRatio == null ? NEUTRAL_SLA : slaRatio,
                slaRatio != null,
                LocalTime.ofInstant(now, operationsZone).getHour(),
                NEUTRAL_TREND);

        WorkforceProposal proposal;
        try {
            proposal = agent.decide(observation);
        } catch (WorkforceServiceUnavailableException exception) {
            LOG.log(System.Logger.Level.WARNING, "Workforce recommendation unavailable: {0}", exception.getMessage());
            throw exception;
        }
        long decisionId = decisionLog.record(observation, proposal, now);
        return new Recommendation(decisionId, now, observation, proposal);
    }

    public record Recommendation(
            long decisionId, Instant generatedAt, WorkforceState observation, WorkforceProposal proposal) {}
}
