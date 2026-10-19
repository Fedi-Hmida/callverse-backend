package com.callverse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.application.exceptions.WorkforceServiceUnavailableException;
import com.callverse.core.application.interfaces.WorkforceAgent;
import com.callverse.core.application.interfaces.WorkforceAgent.WorkforceProposal;
import com.callverse.core.domain.entities.AgentDecision;
import com.callverse.core.domain.enums.AgentType;
import com.callverse.core.domain.enums.SkillCode;
import com.callverse.core.domain.enums.WorkforceActionType;
import com.callverse.infrastructure.persistence.repositories.AgentDecisionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class WorkforceRecommendationIntegrationTest extends AbstractPersistenceTest {

    private static final String RECOMMENDATION = "/api/v1/supervision/workforce/recommendations";

    @MockBean
    private WorkforceAgent agent;

    @Autowired
    private AgentDecisionRepository decisions;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void protectsRouteReadsLiveInputsAndPersistsAnUnapprovedHumanReviewTrace() throws Exception {
        when(agent.decide(any())).thenReturn(new WorkforceProposal(
                WorkforceActionType.REASSIGN,
                SkillCode.CARDS,
                SkillCode.ACCOUNTS,
                1,
                "Review the queue pressure.",
                Map.of(),
                "PPO",
                1,
                true));

        mockMvc.perform(post(RECOMMENDATION)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(RECOMMENDATION).with(as(UserRole.ADVISOR)))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(post(RECOMMENDATION).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode response = objectMapper.readTree(body);

        long decisionId = response.path("decisionId").asLong();
        AgentDecision decision = decisions.findById(decisionId).orElseThrow();
        assertThat(decision.getAgentType()).isEqualTo(AgentType.WORKFORCE_MANAGER);
        assertThat(decision.getRun()).isNull();
        assertThat(decision.getSimTime()).isNull();
        assertThat(decision.getApprovedBy()).isNull();
        assertThat(decision.getObservation().path("queues").has("ACCOUNTS")).isTrue();
        assertThat(decision.getObservation().path("queues").has("CARDS")).isTrue();
        assertThat(decision.getObservation().path("queues").has("CREDIT")).isTrue();
        assertThat(decision.getObservation().path("queues").has("FRAUD")).isTrue();
        assertThat(decision.getAction().path("requiresApproval").asBoolean()).isTrue();
        assertThat(response.path("requiresApproval").asBoolean()).isTrue();
        assertThat(response.path("caveats").toString()).contains("Aucune réaffectation automatique");
        decisions.deleteById(decisionId);
    }

    @Test
    void respondsWithSafe503WhenTheWorkforceServiceIsUnavailable() throws Exception {
        when(agent.decide(any())).thenThrow(new WorkforceServiceUnavailableException("Workforce service is unavailable."));

        mockMvc.perform(post(RECOMMENDATION).with(as(UserRole.SUPERVISOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(WorkforceServiceUnavailableException.CODE))
                .andExpect(jsonPath("$.message").value("Workforce service is unavailable."));
    }

    private static RequestPostProcessor as(UserRole role) {
        UUID userId = UUID.randomUUID();
        return bearer(validToken(userId, userId + "@workforce.test", role));
    }
}
