package com.callverse.persistence;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.callverse.conversation.ConversationFixtures;
import com.callverse.core.application.interfaces.TwinSimulator;
import com.callverse.core.application.interfaces.TwinSimulator.TwinKpis;
import com.callverse.core.domain.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/** The digital twin API: run a scenario under a strategy, read the run, compare the strategies. */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class DigitalTwinEndpointTest extends AbstractPersistenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @MockBean TwinSimulator simulator;

    private UUID scenario;

    @BeforeEach
    void setUp() {
        scenario = new ConversationFixtures(em, tx).scenario("Mardi chargé", "SATURATED", 480, 12);
        when(simulator.run(any())).thenReturn(new TwinKpis(431, 402, 0.91, 0.8512, 0.0673, 41.23, 158.79, 12));
    }

    @Test
    void aSupervisorRunsAScenarioReadsItAndComparesTheStrategies() throws Exception {
        JsonNode created = json(mockMvc.perform(run("THRESHOLD", 7).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.strategyCode").value("THRESHOLD"))
                .andExpect(jsonPath("$.kpis.totalConversations").value(431))
                .andExpect(jsonPath("$.kpis.slaRatio").value(0.8512))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(run("RL_PPO_V1", 7).with(as(UserRole.ADMIN))).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/twin-runs/{id}", created.get("runId").asText()).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.abandonRatio").value(0.0673));
        mockMvc.perform(get("/api/v1/simulation-runs/{id}", created.get("runId").asText())
                        .with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isNotFound());
        JsonNode comparison = json(mockMvc.perform(get("/api/v1/twin-runs").param("scenarioId", scenario.toString())
                        .with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(comparison).extracting(node -> node.get("strategyCode").asText())
                .containsExactlyInAnyOrder("THRESHOLD", "RL_PPO_V1");
    }

    @Test
    void theTwinScreenListsTheScenariosToChooseFrom() throws Exception {
        JsonNode scenarios = json(mockMvc.perform(get("/api/v1/scenarios").with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        JsonNode mine = java.util.stream.StreamSupport.stream(scenarios.spliterator(), false)
                .filter(node -> node.get("id").asText().equals(scenario.toString()))
                .findFirst().orElseThrow();
        assertThat(mine.get("name").asText()).isEqualTo("Mardi chargé");
        assertThat(mine.get("loadProfile").asText()).isEqualTo("SATURATED");
        assertThat(mine.get("durationMinutes").asInt()).isEqualTo(480);
        assertThat(mine.get("advisorCount").asInt()).isEqualTo(12);
        mockMvc.perform(get("/api/v1/scenarios").with(as(UserRole.ADMIN))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/scenarios").with(as(UserRole.ADVISOR))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/scenarios")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlySupervisorsAndAdminsMayRunTheTwin() throws Exception {
        mockMvc.perform(run("THRESHOLD", 1)).andExpect(status().isUnauthorized());
        mockMvc.perform(run("THRESHOLD", 1).with(as(UserRole.ADVISOR))).andExpect(status().isForbidden());
        mockMvc.perform(run("THRESHOLD", 1).with(as(UserRole.CUSTOMER))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/twin-runs").param("scenarioId", scenario.toString()).with(as(UserRole.ADVISOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void errorsSpeakTheUsualEnvelope() throws Exception {
        mockMvc.perform(run("THRESHOLD", 3).with(as(UserRole.SUPERVISOR))).andExpect(status().isCreated());
        mockMvc.perform(run("THRESHOLD", 3).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIMULATION_RUN_ALREADY_EXISTS"));
        mockMvc.perform(run("HUMAN_GUESS", 3).with(as(UserRole.SUPERVISOR))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/twin-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"" + UUID.randomUUID() + "\",\"strategyCode\":\"THRESHOLD\",\"seed\":1}")
                        .with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/twin-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategyCode\":\"THRESHOLD\",\"seed\":-1}").with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/twin-runs/{id}", UUID.randomUUID()).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnavailableTwinAnswers503AndTheRunIsKeptAsFailed() throws Exception {
        when(simulator.run(any())).thenThrow(new IllegalStateException("down"));

        mockMvc.perform(run("STATIC_FIFO", 11).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WORKFORCE_UNAVAILABLE"));
        String failed = tx.execute(s -> (String) em.createNativeQuery(
                        "select r.status from simulation_run r join control_strategy c on c.id = r.strategy_id "
                                + "where r.scenario_id = :s and c.code = 'STATIC_FIFO' and r.seed = 11")
                .setParameter("s", scenario).getSingleResult());
        assertThat(failed).isEqualTo("FAILED");

        // The twin is back: the same scenario, strategy and seed now completes (the seed is not burnt).
        org.mockito.Mockito.reset(simulator);
        when(simulator.run(any())).thenReturn(new TwinKpis(431, 402, 0.91, 0.8512, 0.0673, 41.23, 158.79, 12));
        mockMvc.perform(run("STATIC_FIFO", 11).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void aScenarioBeyondTheTwinsLimitsIsA400NotA503() throws Exception {
        UUID huge = new ConversationFixtures(em, tx).scenario("Armée", "LOW", 60, 400);

        mockMvc.perform(post("/api/v1/twin-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"" + huge + "\",\"strategyCode\":\"THRESHOLD\",\"seed\":1}")
                        .with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder run(String strategyCode, long seed) throws Exception {
        return post("/api/v1/twin-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "scenarioId", scenario.toString(), "strategyCode", strategyCode, "seed", seed)));
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private static RequestPostProcessor as(UserRole role) {
        UUID userId = UUID.randomUUID();
        return bearer(validToken(userId, userId + "@twin.test", role));
    }
}
