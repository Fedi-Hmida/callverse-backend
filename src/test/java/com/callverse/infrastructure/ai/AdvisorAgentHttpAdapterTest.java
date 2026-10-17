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

import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorRequest;
import com.callverse.core.application.interfaces.AdvisorAgent.CustomerContext;
import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.MessageSender;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class AdvisorAgentHttpAdapterTest {

    private static final String BASE_URL = "http://advisor.test";
    private static final String API_KEY = "fake-advisor-key-for-tests";
    private final RestClient.Builder builder = RestClient.builder();
    private MockRestServiceServer server;
    private AdvisorAgentHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new AdvisorAgentHttpAdapter(builder.baseUrl(BASE_URL).build(), API_KEY);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsTheDedicatedKeyAndOnlyKnownCustomerContext() {
        server.expect(requestTo(BASE_URL + "/ai/advisor/respond"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-AI-Key", API_KEY))
                .andExpect(jsonPath("$.conversation_id").isNotEmpty())
                .andExpect(jsonPath("$.customer_id").isNotEmpty())
                .andExpect(jsonPath("$.context.tenure_months").value(24))
                .andExpect(jsonPath("$.context.segment").value("AFFLUENT"))
                .andExpect(jsonPath("$.context.contract_type").doesNotExist())
                .andExpect(jsonPath("$.context.churn_risk").doesNotExist())
                .andExpect(jsonPath("$.context.open_tickets").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "reply": "Je vérifie.",
                          "intent": "FRAUD",
                          "confidence": 0.9,
                          "tool_calls": [],
                          "sources": [],
                          "action": {"type": "NONE", "payload": {}},
                          "latency_ms": 12
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = adapter.respond(request());

        assertThat(response.reply()).isEqualTo("Je vérifie.");
        assertThat(response.confidence()).isEqualTo(0.9);
        assertThat(response.actionType()).isEqualTo("NONE");
    }

    @Test
    void surfacesServerErrorsToTheApplicationLayer() {
        server.expect(requestTo(BASE_URL + "/ai/advisor/respond")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.respond(request())).isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void surfacesTimeoutsToTheApplicationLayer() {
        server.expect(requestTo(BASE_URL + "/ai/advisor/respond"))
                .andRespond(withException(new IOException(new SocketTimeoutException("request timed out"))));

        assertThatThrownBy(() -> adapter.respond(request())).isInstanceOf(ResourceAccessException.class);
    }

    private static AdvisorRequest request() {
        return new AdvisorRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Je ne reconnais pas ce paiement.",
                List.of(new HistoryMessage(
                        MessageSender.ADVISOR, "Bonjour.", Instant.parse("2026-10-17T08:00:00Z"))),
                new CustomerContext(24, CustomerSegment.AFFLUENT));
    }
}
