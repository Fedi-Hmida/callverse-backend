package com.callverse.core.application.features.workforce.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import com.callverse.core.application.interfaces.LiveOperations;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.WorkforceAgent;
import com.callverse.core.application.interfaces.WorkforceAgent.WorkforceProposal;
import com.callverse.core.application.interfaces.WorkforceOperations;
import com.callverse.core.application.interfaces.WorkforceOperations.WorkforceInputs;
import com.callverse.core.domain.enums.SkillCode;
import com.callverse.core.domain.enums.WorkforceActionType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestWorkforceRecommendationCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");

    @Test
    void buildsTrainingCompatibleStateAndLogsProposalForHumanReview() {
        LiveOperations liveOperations = mock(LiveOperations.class);
        when(liveOperations.snapshot(Instant.parse("2026-10-16T22:00:00Z"), NOW))
                .thenReturn(new LiveKpiSnapshot(1, NOW, NOW, List.of(), 0, 0, 0, 0, null, null, null));
        ConversationEvents events =
                new ConversationEvents(mock(RealtimeEventPublisher.class), liveOperations, Clock.fixed(NOW, ZoneId.of("UTC")),
                        ZoneId.of("Europe/Paris"));

        WorkforceInputs inputs = new WorkforceInputs(
                Map.of(SkillCode.ACCOUNTS, 4L, SkillCode.CARDS, 3L, SkillCode.CREDIT, 2L, SkillCode.FRAUD, 1L),
                Map.of(SkillCode.ACCOUNTS, 60.0, SkillCode.CARDS, 30.0, SkillCode.CREDIT, 20.0, SkillCode.FRAUD, 10.0),
                Map.of(SkillCode.ACCOUNTS, 0, SkillCode.CARDS, 2, SkillCode.CREDIT, 1, SkillCode.FRAUD, 0));
        AtomicReference<WorkforceAgent.WorkforceState> recordedState = new AtomicReference<>();
        WorkforceProposal proposal = new WorkforceProposal(
                WorkforceActionType.NONE, null, null, null, "No change proposed.", Map.of(), "PPO", 0, true);
        RequestWorkforceRecommendationCommandHandler handler = new RequestWorkforceRecommendationCommandHandler(
                now -> inputs,
                liveOperations,
                events,
                state -> {
                    assertThat(state.queues()).isEqualTo(inputs.queues());
                    assertThat(state.averageWaitSeconds()).isEqualTo(inputs.averageWaitSeconds());
                    assertThat(state.available()).isEqualTo(inputs.available());
                    assertThat(state.slaToday()).isEqualTo(1.0);
                    assertThat(state.slaMeasured()).isFalse();
                    assertThat(state.hour()).isEqualTo(10);
                    assertThat(state.trend()).isEqualTo(1.0);
                    return proposal;
                },
                (state, action, at) -> {
                    recordedState.set(state);
                    assertThat(action).isEqualTo(proposal);
                    assertThat(at).isEqualTo(NOW);
                    return 42L;
                },
                Clock.fixed(NOW, ZoneId.of("UTC")),
                ZoneId.of("Europe/Paris"));

        var result = handler.handle();

        assertThat(result.decisionId()).isEqualTo(42L);
        assertThat(result.generatedAt()).isEqualTo(NOW);
        assertThat(result.proposal()).isEqualTo(proposal);
        assertThat(recordedState.get()).isNotNull();
    }
}
