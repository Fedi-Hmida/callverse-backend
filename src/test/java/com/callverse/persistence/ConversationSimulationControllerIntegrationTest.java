package com.callverse.persistence;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.callverse.core.application.interfaces.AdvisorAgent;
import com.callverse.core.application.interfaces.SimulatorAgent;
import com.callverse.core.application.interfaces.SimulatorAgent.Outcome;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorResponse;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorState;
import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.LoadProfile;
import com.callverse.core.domain.enums.SimulatorProfile;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.infrastructure.persistence.repositories.ScenarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Transactional
class ConversationSimulationControllerIntegrationTest extends AbstractPersistenceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScenarioRepository scenarios;

    @MockBean
    private SimulatorAgent simulator;

    @MockBean
    private AdvisorAgent advisor;

    @MockBean(name = "conversationSimulationExecutor")
    private TaskExecutor executor;

    @BeforeEach
    void runScheduledTasksInlineForDeterministicAssertions() {
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(executor)
                .execute(any(Runnable.class));
    }

    @Test
    void protectsRunManagementAndUsesOnlySyntheticSimulatorAndAdvisorFlow() throws Exception {
        Scenario scenario = scenario();
        SimulatorState terminalState =
                new SimulatorState(SimulatorProfile.CALME, 75, 90, "Comprendre un débit", true);
        when(simulator.nextMessage(any()))
                .thenReturn(new SimulatorResponse(null, Outcome.RESOLVED, terminalState));
        String request = objectMapper.writeValueAsString(new StartRequest(
                scenario.getId(),
                "STATIC_FIFO",
                90210L,
                SimulatorProfile.CALME,
                "Comprendre un débit",
                65.0,
                70.0,
                4,
                CustomerSegment.AFFLUENT,
                18));

        mockMvc.perform(post("/api/v1/simulation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/simulation-runs")
                        .with(as(UserRole.ADVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());

        String response = mockMvc.perform(post("/api/v1/simulation-runs")
                        .with(as(UserRole.SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String runId = objectMapper.readTree(response).path("runId").asText();

        mockMvc.perform(get("/api/v1/simulation-runs/{runId}", runId).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.conversationStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.messageCount").value(0));
        verify(simulator).nextMessage(any());
        verifyNoInteractions(advisor);
    }

    private Scenario scenario() {
        Scenario scenario = new Scenario();
        scenario.setName("api-conversation-simulation-" + UUID.randomUUID());
        scenario.setLoadProfile(LoadProfile.LOW);
        scenario.setDurationMinutes(1);
        scenario.setAdvisorCount(1);
        scenario.setSkillDistribution(objectMapper.createObjectNode());
        scenario.setCustomerProfileMix(objectMapper.createObjectNode());
        return scenarios.saveAndFlush(scenario);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(UserRole role) {
        UUID userId = UUID.randomUUID();
        return bearer(validToken(userId, userId + "@simulation.test", role));
    }

    private record StartRequest(
            UUID scenarioId,
            String strategyCode,
            long seed,
            SimulatorProfile profile,
            String objective,
            double patience,
            double satisfaction,
            int maxTurns,
            CustomerSegment customerSegment,
            int tenureMonths) {}
}
