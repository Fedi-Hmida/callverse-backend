package com.callverse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorResponse;
import com.callverse.core.application.interfaces.AdvisorAgent.Source;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorRequest;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorResponse;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorState;
import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.enums.AgentType;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.LoadProfile;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.RunStatus;
import com.callverse.core.domain.enums.SimulatorProfile;
import com.callverse.infrastructure.persistence.repositories.AgentDecisionRepository;
import com.callverse.infrastructure.persistence.repositories.ControlStrategyRepository;
import com.callverse.infrastructure.persistence.repositories.MessageRepository;
import com.callverse.infrastructure.persistence.repositories.ScenarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ConversationSimulationIntegrationTest extends AbstractPersistenceTest {

    @Autowired
    private ConversationSimulationStore store;

    @Autowired
    private ScenarioRepository scenarios;

    @Autowired
    private ControlStrategyRepository strategies;

    @Autowired
    private MessageRepository messages;

    @Autowired
    private AgentDecisionRepository decisions;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void persistsOnlyLinkedSyntheticConversationAndBothAgentTraces() {
        Instant now = Instant.parse("2026-10-17T08:00:00Z");
        SimulationSetup setup = setup();
        var started = store.start(setup, now);
        UUID conversationId = started.conversationId();
        UUID runId = started.runId();
        AdvisorResponse advisorResponse = new AdvisorResponse(
                "Votre débit correspond à votre abonnement.",
                "BILLING_QUESTION",
                0.91,
                List.of(),
                List.of(new Source(UUID.fromString("11111111-2222-3333-4444-555555555555"), 0.87)),
                "NONE",
                Map.of(),
                13);
        SimulatorState state = new SimulatorState(
                SimulatorProfile.CALME, 80, 85, setup.objective(), true);
        SimulatorRequest simulatorRequest = new SimulatorRequest(
                conversationId,
                List.of(),
                null,
                0,
                state);
        SimulatorResponse simulatorResponse =
                new SimulatorResponse(null, com.callverse.core.application.interfaces.SimulatorAgent.Outcome.RESOLVED, state);

        store.appendCustomerMessage(conversationId, "Je ne comprends pas ce débit.", now);
        store.appendAdvisorMessage(conversationId, advisorResponse, now.plusSeconds(1));
        store.recordSimulatorDecision(runId, 0, simulatorRequest, simulatorResponse);
        store.recordAdvisorDecision(runId, 1, "Je ne comprends pas ce débit.", advisorResponse);
        store.complete(runId, conversationId, ConversationStatus.RESOLVED, now.plusSeconds(2));

        var completed = store.find(runId).orElseThrow();
        List<com.callverse.core.domain.entities.Message> transcript =
                messages.findByConversationIdOrderBySentAtAsc(conversationId);
        var trace = decisions.findByRunIdOrderBySimTimeAsc(runId);

        assertThat(started.runStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(started.customerId()).isNotNull();
        assertThat(completed.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.conversationStatus()).isEqualTo(ConversationStatus.RESOLVED);
        assertThat(completed.messageCount()).isEqualTo(2);
        assertThat(transcript).extracting(com.callverse.core.domain.entities.Message::getSender)
                .containsExactly(MessageSender.CUSTOMER, MessageSender.ADVISOR);
        assertThat(transcript.get(0).isAiGenerated()).isFalse();
        assertThat(transcript.get(1).isAiGenerated()).isTrue();
        assertThat(transcript.get(1).getSources()).hasSize(1);
        assertThat(transcript.get(1).getToolCalls()).isEmpty();
        assertThat(trace).extracting(com.callverse.core.domain.entities.AgentDecision::getAgentType)
                .containsExactly(AgentType.CLIENT_SIMULATOR, AgentType.CUSTOMER_ADVISOR);
        assertThat(trace.get(0).getRun().getId()).isEqualTo(runId);
    }

    private SimulationSetup setup() {
        Scenario scenario = new Scenario();
        scenario.setName("conversation-simulation-" + UUID.randomUUID());
        scenario.setLoadProfile(LoadProfile.LOW);
        scenario.setDurationMinutes(1);
        scenario.setAdvisorCount(1);
        scenario.setSkillDistribution(objectMapper.createObjectNode());
        scenario.setCustomerProfileMix(objectMapper.createObjectNode());
        scenarios.saveAndFlush(scenario);
        return new SimulationSetup(
                scenario.getId(),
                strategies.findByCode("STATIC_FIFO").orElseThrow().getCode(),
                UUID.randomUUID().getMostSignificantBits(),
                SimulatorProfile.CALME,
                "Comprendre un débit",
                70,
                75,
                3,
                CustomerSegment.AFFLUENT,
                18);
    }
}
