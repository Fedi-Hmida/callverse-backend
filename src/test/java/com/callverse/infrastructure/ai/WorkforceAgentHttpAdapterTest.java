package com.callverse.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.callverse.core.application.exceptions.WorkforceServiceUnavailableException;
import com.callverse.core.application.interfaces.WorkforceAgent.WorkforceState;
import com.callverse.core.domain.enums.SkillCode;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WorkforceAgentHttpAdapterTest {

    private static final String BASE_URL = "http://workforce.test";
    private static final String API_KEY = "fake-ai-key-for-tests-only";

    private final RestClient.Builder builder = RestClient.builder();
    private MockRestServiceServer server;
    private WorkforceAgentHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new WorkforceAgentHttpAdapter(builder.baseUrl(BASE_URL).build(), API_KEY);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsLiveStateAndDedicatedKeyThenReturnsAnUnappliedProposal() {
        server.expect(requestTo(BASE_URL + "/ai/workforce/decide?strategy=PPO"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-AI-Key", API_KEY))
                .andExpect(jsonPath("$.queues.ACCOUNTS").value(4))
                .andExpect(jsonPath("$.avg_wait.CARDS").value(30.0))
                .andExpect(jsonPath("$.available.FRAUD").value(0))
                .andExpect(jsonPath("$.sla_today").value(0.75))
                .andExpect(jsonPath("$.hour").value(10))
                .andExpect(jsonPath("$.trend").value(1.0))
                .andRespond(withSuccess("""
                        {
                          "type": "REASSIGN",
                          "from_pool": "CARDS",
                          "to_pool": "ACCOUNTS",
                          "count": 1,
                          "reason": "Support the longest queue.",
                          "expected_gain": {},
                          "strategy": "PPO",
                          "action_id": 1,
                          "requires_approval": true
                        }
                        """, MediaType.APPLICATION_JSON));

        var proposal = adapter.decide(state());

        assertThat(proposal.type().name()).isEqualTo("REASSIGN");
        assertThat(proposal.fromPool()).isEqualTo(SkillCode.CARDS);
        assertThat(proposal.toPool()).isEqualTo(SkillCode.ACCOUNTS);
        assertThat(proposal.requiresApproval()).isTrue();
    }

    @Test
    void translatesServiceErrorsToSafeUnavailableException() {
        server.expect(requestTo(BASE_URL + "/ai/workforce/decide?strategy=PPO")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.decide(state()))
                .isInstanceOf(WorkforceServiceUnavailableException.class)
                .hasMessage("Workforce service is unavailable.");
    }

    @Test
    void translatesTimeoutsToSafeUnavailableException() {
        server.expect(requestTo(BASE_URL + "/ai/workforce/decide?strategy=PPO"))
                .andRespond(withException(new IOException(new SocketTimeoutException("request timed out"))));

        assertThatThrownBy(() -> adapter.decide(state()))
                .isInstanceOf(WorkforceServiceUnavailableException.class)
                .hasMessage("Workforce service is unavailable.");
    }

    @Test
    void rejectsProposalsThatDoNotRequireHumanApproval() {
        server.expect(requestTo(BASE_URL + "/ai/workforce/decide?strategy=PPO"))
                .andRespond(withSuccess("""
                        {
                          "type": "REASSIGN",
                          "from_pool": "CARDS",
                          "to_pool": "ACCOUNTS",
                          "count": 1,
                          "reason": "Do not apply automatically.",
                          "expected_gain": {},
                          "strategy": "PPO",
                          "action_id": 1,
                          "requires_approval": false
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.decide(state()))
                .isInstanceOf(WorkforceServiceUnavailableException.class)
                .hasMessage("Workforce service returned an invalid proposal.");
    }

    private static WorkforceState state() {
        return new WorkforceState(
                Map.of(SkillCode.ACCOUNTS, 4L, SkillCode.CARDS, 3L, SkillCode.CREDIT, 2L, SkillCode.FRAUD, 1L),
                Map.of(SkillCode.ACCOUNTS, 60.0, SkillCode.CARDS, 30.0, SkillCode.CREDIT, 20.0, SkillCode.FRAUD, 10.0),
                Map.of(SkillCode.ACCOUNTS, 0, SkillCode.CARDS, 2, SkillCode.CREDIT, 1, SkillCode.FRAUD, 0),
                0.75,
                true,
                10,
                1.0);
    }
}
