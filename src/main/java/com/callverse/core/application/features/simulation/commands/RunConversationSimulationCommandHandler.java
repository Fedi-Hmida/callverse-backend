package com.callverse.core.application.features.simulation.commands;

import com.callverse.core.application.interfaces.AdvisorAgent;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorRequest;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorResponse;
import com.callverse.core.application.interfaces.AdvisorAgent.CustomerContext;
import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.application.interfaces.ConversationSimulationStore.TranscriptMessage;
import com.callverse.core.application.interfaces.SimulatorAgent;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorRequest;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorResponse;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorState;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.RunStatus;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Runs a Simulator → Advisor dialogue without entering the live-conversation workflow. */
public class RunConversationSimulationCommandHandler {

    private static final Logger LOG = System.getLogger(RunConversationSimulationCommandHandler.class.getName());
    private static final int HISTORY_LIMIT = 20;
    private final SimulatorAgent simulator;
    private final AdvisorAgent advisor;
    private final ConversationSimulationStore store;
    private final Clock clock;

    public RunConversationSimulationCommandHandler(
            SimulatorAgent simulator, AdvisorAgent advisor, ConversationSimulationStore store, Clock clock) {
        this.simulator = Objects.requireNonNull(simulator, "simulator must not be null");
        this.advisor = Objects.requireNonNull(advisor, "advisor must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void handle(UUID runId, SimulationSetup setup) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(setup, "setup must not be null");
        try {
            ConversationSimulationStore.SimulationRecord run = store.find(runId).orElse(null);
            if (run == null || run.runStatus() != RunStatus.RUNNING) {
                return;
            }
            Instant startedAt = clock.instant();
            SimulatorState state = new SimulatorState(
                    setup.profile(), setup.patience(), setup.satisfaction(), setup.objective(), false);
            String lastAdvisorReply = null;

            for (int turn = 1; turn <= setup.maxTurns(); turn++) {
                SimulatorResponse next = next(run, state, lastAdvisorReply, startedAt);
                state = next.state();
                if (finishIfTerminal(runId, run.conversationId(), next.outcome())) {
                    return;
                }

                TranscriptMessage customerMessage =
                        store.appendCustomerMessage(run.conversationId(), next.content(), clock.instant());
                List<HistoryMessage> history = recentHistory(store.transcript(run.conversationId()).stream()
                        .filter(message -> message.id() != customerMessage.id())
                        .toList());

                AdvisorResponse response = advisor.respond(new AdvisorRequest(
                        run.conversationId(),
                        run.customerId(),
                        customerMessage.content(),
                        history,
                        new CustomerContext(setup.tenureMonths(), setup.customerSegment()),
                        AdvisorAgent.Mode.SIMULATION));
                validateAdvisorResponse(response);
                store.appendAdvisorMessage(run.conversationId(), response, clock.instant());
                store.recordAdvisorDecision(
                        runId, simTime(startedAt), customerMessage.content(), response);
                if ("ESCALATE".equals(response.actionType())) {
                    // A handoff is a legitimate terminal outcome of an isolated dialogue.
                    // Do not make the client repeatedly ask an agent that has handed over.
                    store.complete(runId, run.conversationId(), ConversationStatus.ESCALATED, clock.instant());
                    return;
                }
                lastAdvisorReply = response.reply();
            }

            SimulatorResponse finalCheck = next(run, state, lastAdvisorReply, startedAt);
            if (finishIfTerminal(runId, run.conversationId(), finalCheck.outcome())) {
                return;
            }
            store.fail(runId, "Maximum dialogue turn limit reached before a terminal Simulator state.", clock.instant());
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "Conversation simulation failed for run {0}", runId);
            store.fail(runId, "Simulation agent unavailable or returned an invalid response.", clock.instant());
        }
    }

    private SimulatorResponse next(
            ConversationSimulationStore.SimulationRecord run, SimulatorState state, String lastAdvisorReply, Instant startedAt) {
        List<HistoryMessage> history = recentHistory(store.transcript(run.conversationId()));
        SimulatorRequest request = new SimulatorRequest(
                run.conversationId(), history, lastAdvisorReply, 0, state);
        SimulatorResponse response = simulator.nextMessage(request);
        store.recordSimulatorDecision(run.runId(), simTime(startedAt), request, response);
        return response;
    }

    private static List<HistoryMessage> recentHistory(List<TranscriptMessage> transcript) {
        List<HistoryMessage> history = transcript.stream()
                .filter(message -> message.sender() == MessageSender.CUSTOMER
                        || message.sender() == MessageSender.ADVISOR)
                .map(message -> new HistoryMessage(message.sender(), message.content(), message.sentAt()))
                .toList();
        int firstRecentMessage = Math.max(0, history.size() - HISTORY_LIMIT);
        return history.subList(firstRecentMessage, history.size());
    }

    private boolean finishIfTerminal(UUID runId, UUID conversationId, SimulatorAgent.Outcome outcome) {
        ConversationStatus terminal = switch (outcome) {
            case RESOLVED -> ConversationStatus.RESOLVED;
            case ABANDONED -> ConversationStatus.ABANDONED;
            case IN_PROGRESS -> null;
        };
        if (terminal == null) {
            return false;
        }
        store.complete(runId, conversationId, terminal, clock.instant());
        return true;
    }

    private int simTime(Instant startedAt) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, Duration.between(startedAt, clock.instant()).toSeconds()));
    }

    private static void validateAdvisorResponse(AdvisorResponse response) {
        if (response == null
                || response.reply() == null
                || response.reply().isBlank()
                || response.intent() == null
                || !Double.isFinite(response.confidence())
                || response.confidence() < 0
                || response.confidence() > 1) {
            throw new IllegalArgumentException("Advisor returned an invalid simulation response.");
        }
    }
}
