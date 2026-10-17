package com.callverse.infrastructure.ai;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorRequest;
import com.callverse.core.application.interfaces.AdvisorAgent.CustomerContext;
import com.callverse.core.application.interfaces.AdvisorAgent.Mode;
import com.callverse.core.domain.enums.CustomerSegment;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AdvisorSimulationModeHttpAdapterTest {

    private static final String BASE_URL = "http://advisor.test";
    private final RestClient.Builder builder = RestClient.builder();
    private MockRestServiceServer server;
    private AdvisorAgentHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new AdvisorAgentHttpAdapter(builder.baseUrl(BASE_URL).build(), "");
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsSimulationModeToDisableBusinessToolsOnTheAdvisor() {
        server.expect(requestTo(BASE_URL + "/ai/advisor/respond"))
                .andExpect(jsonPath("$.mode").value("SIMULATION"))
                .andRespond(withSuccess("""
                        {
                          "reply": "Le débit correspond à votre abonnement.",
                          "intent": "BILLING_QUESTION",
                          "confidence": 0.9,
                          "tool_calls": [],
                          "sources": [],
                          "action": {"type": "NONE", "payload": {}},
                          "latency_ms": 10
                        }
                        """, MediaType.APPLICATION_JSON));

        adapter.respond(new AdvisorRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Je ne comprends pas ce débit.",
                List.of(),
                new CustomerContext(18, CustomerSegment.AFFLUENT),
                Mode.SIMULATION));
    }
}
