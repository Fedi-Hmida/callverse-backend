package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.domain.enums.SimulatorProfile;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Port to the simulated-customer agent; each invocation produces or ends one dialogue turn. */
public interface SimulatorAgent {

    SimulatorResponse nextMessage(SimulatorRequest request);

    record SimulatorState(
            SimulatorProfile profile,
            double patience,
            double satisfaction,
            String objective,
            boolean objectiveMet) {

        public SimulatorState {
            Objects.requireNonNull(profile, "profile must not be null");
            if (!Double.isFinite(patience) || patience < 0 || patience > 100) {
                throw new IllegalArgumentException("patience must be between 0 and 100");
            }
            if (!Double.isFinite(satisfaction) || satisfaction < 0 || satisfaction > 100) {
                throw new IllegalArgumentException("satisfaction must be between 0 and 100");
            }
            Objects.requireNonNull(objective, "objective must not be null");
        }
    }

    record SimulatorRequest(
            UUID conversationId,
            List<HistoryMessage> history,
            String lastAdvisorReply,
            int waitSeconds,
            SimulatorState state) {

        public SimulatorRequest {
            Objects.requireNonNull(conversationId, "conversationId must not be null");
            history = List.copyOf(Objects.requireNonNull(history, "history must not be null"));
            if (waitSeconds < 0) {
                throw new IllegalArgumentException("waitSeconds must not be negative");
            }
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    enum Outcome {
        IN_PROGRESS,
        RESOLVED,
        ABANDONED
    }

    record SimulatorResponse(String content, Outcome outcome, SimulatorState state) {

        public SimulatorResponse {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(state, "state must not be null");
            if (outcome == Outcome.IN_PROGRESS && (content == null || content.isBlank())) {
                throw new IllegalArgumentException("an in-progress Simulator response must contain a message");
            }
            if (outcome != Outcome.IN_PROGRESS && content != null) {
                throw new IllegalArgumentException("a terminal Simulator response must not contain a message");
            }
        }
    }
}
