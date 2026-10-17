package com.callverse.persistence;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.QualityEvaluation;
import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.enums.*;
import com.callverse.infrastructure.persistence.repositories.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Transactional
class AiWorkspaceIntegrationTest extends AbstractPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ScenarioRepository scenarios;
    @Autowired ConversationSimulationStore simulations;
    @Autowired CustomerRepository customers;
    @Autowired ConversationRepository conversations;
    @Autowired QualityEvaluationRepository evaluations;

    @Test
    void everyReadRequiresSupervisionEvenInTheSecurityFilterChain() throws Exception {
        for (String route : new String[] {"/simulation-runs/options", "/simulation-runs",
                "/simulation-runs/" + UUID.randomUUID() + "/messages",
                "/supervision/conversations/" + UUID.randomUUID() + "/quality"}) {
            mvc.perform(get("/api/v1" + route)).andExpect(status().isUnauthorized());
            for (UserRole role : new UserRole[] {UserRole.CUSTOMER, UserRole.ADVISOR}) {
                mvc.perform(get("/api/v1" + route).with(as(role))).andExpect(status().isForbidden());
            }
        }
        mvc.perform(get("/api/v1/simulation-runs/options").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategies").isArray());
        mvc.perform(post("/api/v1/simulation-runs/scenarios").with(as(UserRole.ADVISOR))
                .contentType("application/json").content("{\"name\":\"Dialogue\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/simulation-runs/scenarios").with(as(UserRole.ADMIN))
                .contentType("application/json").content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/simulation-runs/scenarios").with(as(UserRole.SUPERVISOR))
                .contentType("application/json").content("{\"name\":\" Dialogue de test \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Dialogue de test"));
        mvc.perform(get("/api/v1/simulation-runs/" + UUID.randomUUID() + "/messages").with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isNotFound());
    }

    @Test
    void discoversExistingScenariosAndReadsSyntheticTranscriptWithoutOpeningTheLiveConversation() throws Exception {
        Scenario scenario = new Scenario();
        scenario.setName("workspace-" + UUID.randomUUID()); scenario.setLoadProfile(LoadProfile.LOW);
        scenario.setDurationMinutes(1); scenario.setAdvisorCount(1);
        scenario.setSkillDistribution(json.createObjectNode()); scenario.setCustomerProfileMix(json.createObjectNode());
        scenarios.saveAndFlush(scenario);
        var run = simulations.start(new SimulationSetup(scenario.getId(), "STATIC_FIFO", 123456,
                SimulatorProfile.CALME, "Comprendre un débit", 100, 50, 3, CustomerSegment.MASS, 12), Instant.now());
        simulations.appendCustomerMessage(run.conversationId(), "Quel est ce débit ?", Instant.now());
        String catalog = mvc.perform(get("/api/v1/simulation-runs/options").with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(catalog).contains(scenario.getId().toString()).doesNotContain("customerProfileMix");
        mvc.perform(get("/api/v1/simulation-runs").with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].runId").value(run.runId().toString()));
        mvc.perform(get("/api/v1/simulation-runs/" + run.runId() + "/messages").with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].content").value("Quel est ce débit ?"))
                .andExpect(jsonPath("$[0].sources").doesNotExist()).andExpect(jsonPath("$[0].toolCalls").doesNotExist());
        mvc.perform(get("/api/v1/conversations/" + run.conversationId()).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isNotFound());
    }

    @Test
    void readsPersistedQualityAndDistinguishesPendingFromUnknownConversation() throws Exception {
        Customer customer = new Customer();
        customer.setExternalRef("Q-" + UUID.randomUUID()); customer.setFirstName("Test"); customer.setLastName("Client");
        customer.setRegion("TEST"); customer.setSegment(CustomerSegment.MASS); customer.setTenureMonths(12);
        customers.saveAndFlush(customer);
        Conversation conversation = new Conversation(); conversation.setCustomer(customer);
        conversation.setStatus(ConversationStatus.RESOLVED); conversation.setQueuedAt(Instant.now()); conversation.setEndedAt(Instant.now());
        conversations.saveAndFlush(conversation);
        String route = "/api/v1/supervision/conversations/" + conversation.getId() + "/quality";
        mvc.perform(get(route).with(as(UserRole.SUPERVISOR))).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        QualityEvaluation evaluation = new QualityEvaluation(); evaluation.setConversation(conversation);
        evaluation.setGlobalScore(new BigDecimal("8.50")); evaluation.setEvaluator(EvaluatorType.AI);
        evaluation.setScores(json.createObjectNode().put("EMPATHY", 8)); evaluation.setExplanation("Échange clair.");
        evaluation.setFlags(json.createObjectNode().put("private_trace", "not exposed")); evaluations.saveAndFlush(evaluation);
        mvc.perform(get(route).with(as(UserRole.SUPERVISOR))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].globalScore").value(8.5)).andExpect(jsonPath("$[0].scores.EMPATHY").value(8))
                .andExpect(jsonPath("$[0].flags").doesNotExist());
        mvc.perform(get("/api/v1/supervision/conversations/" + UUID.randomUUID() + "/quality").with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor as(UserRole role) {
        UUID id = UUID.randomUUID(); return bearer(validToken(id, id + "@workspace.test", role));
    }
}
