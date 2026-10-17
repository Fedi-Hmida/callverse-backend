package com.callverse.core.application.features.simulation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.features.simulation.commands.RunConversationSimulationCommandHandler;
import com.callverse.core.application.interfaces.AdvisorAgent;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorRequest;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorResponse;
import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.application.interfaces.AdvisorAgent.Source;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationRecord;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.application.interfaces.ConversationSimulationStore.TranscriptMessage;
import com.callverse.core.application.interfaces.SimulatorAgent;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorRequest;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorResponse;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorState;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.RunStatus;
import com.callverse.core.domain.enums.SimulatorProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunConversationSimulationCommandHandlerTest {

    private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONVERSATION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CUSTOMER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");

    private final SimulatorAgent simulator = mock(SimulatorAgent.class);
    private final AdvisorAgent advisor = mock(AdvisorAgent.class);
    private final ConversationSimulationStore store = mock(ConversationSimulationStore.class);
    private RunConversationSimulationCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RunConversationSimulationCommandHandler(
                simulator,
                advisor,
                store,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(store.find(RUN_ID)).thenReturn(Optional.of(new SimulationRecord(
                RUN_ID,
                CONVERSATION_ID,
                CUSTOMER_ID,
                RunStatus.RUNNING,
                ConversationStatus.ACTIVE,
                0,
                null,
                NOW,
                null)));
    }

    @Test
    void runsSyntheticCustomerAndAdvisorTurnsUntilTheSimulatorEndsTheConversation() {
        TranscriptMessage customerMessage =
                new TranscriptMessage(1, MessageSender.CUSTOMER, "J'ai un débit inconnu.", NOW);
        TranscriptMessage advisorMessage =
                new TranscriptMessage(2, MessageSender.ADVISOR, "Le débit est expliqué.", NOW);
        when(store.transcript(CONVERSATION_ID))
                .thenReturn(List.of(), List.of(customerMessage), List.of(customerMessage, advisorMessage));
        when(store.appendCustomerMessage(CONVERSATION_ID, "J'ai un débit inconnu.", NOW)).thenReturn(customerMessage);
        SimulatorState inProgress = new SimulatorState(
                SimulatorProfile.CALME, 65, 75, "Comprendre un débit", false);
        SimulatorState resolved = new SimulatorState(
                SimulatorProfile.CALME, 70, 90, "Comprendre un débit", true);
        when(simulator.nextMessage(any()))
                .thenReturn(new SimulatorResponse("J'ai un débit inconnu.", SimulatorAgent.Outcome.IN_PROGRESS, inProgress))
                .thenReturn(new SimulatorResponse(null, SimulatorAgent.Outcome.RESOLVED, resolved));
        AdvisorResponse advisorResponse = new AdvisorResponse(
                "Le débit est expliqué.",
                "BILLING_QUESTION",
                0.95,
                List.of(),
                List.of(new Source(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"), 0.91)),
                "NONE",
                Map.of(),
                19);
        when(advisor.respond(any())).thenReturn(advisorResponse);

        handler.handle(RUN_ID, setup());

        verify(advisor).respond(any(AdvisorRequest.class));
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(AdvisorRequest.class);
        verify(advisor).respond(requestCaptor.capture());
        assertThat(requestCaptor.getValue().mode()).isEqualTo(AdvisorAgent.Mode.SIMULATION);
        assertThat(requestCaptor.getValue().history()).isEmpty();
        assertThat(requestCaptor.getValue().context().segment()).isEqualTo(CustomerSegment.AFFLUENT);
        verify(store).appendCustomerMessage(CONVERSATION_ID, "J'ai un débit inconnu.", NOW);
        verify(store).appendAdvisorMessage(CONVERSATION_ID, advisorResponse, NOW);
        verify(store).recordAdvisorDecision(RUN_ID, 0, "J'ai un débit inconnu.", advisorResponse);
        verify(store).complete(RUN_ID, CONVERSATION_ID, ConversationStatus.RESOLVED, NOW);
        verify(store, never()).fail(eq(RUN_ID), any(), any());
    }

    @Test
    void capsBothAgentHistoriesAtTwentyAndExcludesSystemAndCurrentMessagesFromAdvisorHistory() {
        List<TranscriptMessage> previousMessages = IntStream.rangeClosed(1, 24)
                .mapToObj(index -> new TranscriptMessage(
                        index,
                        index == 3 ? MessageSender.SYSTEM : MessageSender.CUSTOMER,
                        "history-" + index,
                        NOW.plusSeconds(index)))
                .toList();
        TranscriptMessage currentCustomer =
                new TranscriptMessage(25, MessageSender.CUSTOMER, "current customer turn", NOW.plusSeconds(25));
        TranscriptMessage currentAdvisor =
                new TranscriptMessage(26, MessageSender.ADVISOR, "current advisor turn", NOW.plusSeconds(26));
        when(store.transcript(CONVERSATION_ID))
                .thenReturn(
                        previousMessages,
                        append(previousMessages, currentCustomer),
                        append(previousMessages, currentCustomer, currentAdvisor));
        when(store.appendCustomerMessage(CONVERSATION_ID, "current customer turn", NOW))
                .thenReturn(currentCustomer);
        SimulatorState state = setupState();
        when(simulator.nextMessage(any()))
                .thenReturn(
                        new SimulatorResponse("current customer turn", SimulatorAgent.Outcome.IN_PROGRESS, state),
                        new SimulatorResponse(null, SimulatorAgent.Outcome.RESOLVED, state));
        when(advisor.respond(any())).thenReturn(new AdvisorResponse(
                "current advisor turn", "GENERAL", 0.9, List.of(), List.of(), "NONE", Map.of(), 1));

        handler.handle(RUN_ID, setup());

        var advisorCaptor = org.mockito.ArgumentCaptor.forClass(AdvisorRequest.class);
        verify(advisor).respond(advisorCaptor.capture());
        assertThat(advisorCaptor.getValue().history()).hasSize(20);
        assertThat(advisorCaptor.getValue().history())
                .extracting(HistoryMessage::content)
                .containsExactly(
                        "history-5",
                        "history-6",
                        "history-7",
                        "history-8",
                        "history-9",
                        "history-10",
                        "history-11",
                        "history-12",
                        "history-13",
                        "history-14",
                        "history-15",
                        "history-16",
                        "history-17",
                        "history-18",
                        "history-19",
                        "history-20",
                        "history-21",
                        "history-22",
                        "history-23",
                        "history-24");
        var simulatorCaptor = org.mockito.ArgumentCaptor.forClass(SimulatorRequest.class);
        verify(simulator, org.mockito.Mockito.times(2)).nextMessage(simulatorCaptor.capture());
        assertThat(simulatorCaptor.getAllValues().getFirst().history()).hasSize(20);
        assertThat(simulatorCaptor.getAllValues().getFirst().history().getLast().content()).isEqualTo("history-24");
    }

    @Test
    void leavesTheSimulationUnresolvedWhenTheAdvisorIsUnavailable() {
        when(store.transcript(CONVERSATION_ID))
                .thenReturn(
                        List.of(),
                        List.of(new TranscriptMessage(1, MessageSender.CUSTOMER, "Je suis inquiet.", NOW)));
        when(store.appendCustomerMessage(CONVERSATION_ID, "Je suis inquiet.", NOW))
                .thenReturn(new TranscriptMessage(1, MessageSender.CUSTOMER, "Je suis inquiet.", NOW));
        when(simulator.nextMessage(any())).thenReturn(new SimulatorResponse(
                "Je suis inquiet.", SimulatorAgent.Outcome.IN_PROGRESS, setupState()));
        when(advisor.respond(any())).thenThrow(new IllegalStateException("sensitive remote details"));

        handler.handle(RUN_ID, setup());

        verify(store).fail(
                RUN_ID,
                "Simulation agent unavailable or returned an invalid response.",
                NOW);
        verify(store, never()).complete(any(), any(), any(), any());
    }

    @Test
    void ignoresRunsThatAreNoLongerRunning() {
        when(store.find(RUN_ID)).thenReturn(Optional.of(new SimulationRecord(
                RUN_ID,
                CONVERSATION_ID,
                CUSTOMER_ID,
                RunStatus.COMPLETED,
                ConversationStatus.RESOLVED,
                2,
                null,
                NOW,
                NOW)));

        handler.handle(RUN_ID, setup());

        verify(simulator, never()).nextMessage(any());
        verify(advisor, never()).respond(any());
    }

    private static SimulationSetup setup() {
        return new SimulationSetup(
                UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                "STATIC_FIFO",
                7,
                SimulatorProfile.CALME,
                "Comprendre un débit",
                60,
                70,
                5,
                CustomerSegment.AFFLUENT,
                24);
    }

    private static SimulatorState setupState() {
        return new SimulatorState(SimulatorProfile.CALME, 60, 70, "Comprendre un débit", false);
    }

    @SafeVarargs
    private static <T> List<T> append(List<T> source, T... values) {
        return java.util.stream.Stream.concat(source.stream(), java.util.Arrays.stream(values)).toList();
    }
}
