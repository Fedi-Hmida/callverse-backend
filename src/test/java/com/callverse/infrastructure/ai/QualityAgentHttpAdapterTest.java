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

import com.callverse.core.application.exceptions.QualityServiceUnavailableException;
import com.callverse.core.application.interfaces.QualityAgent.QualityMessage;
import com.callverse.core.application.interfaces.QualityAgent.QualityRequest;
import com.callverse.core.domain.enums.MessageSender;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QualityAgentHttpAdapterTest {

    private static final String BASE_URL = "http://quality.test";
    private static final String API_KEY = "fake-ai-key-for-tests-only";
    private static final UUID CONVERSATION_ID = UUID.fromString("182fe212-078b-4cf1-a8a7-7a161df3988b");

    private final RestClient.Builder builder = RestClient.builder();
    private MockRestServiceServer server;
    private QualityAgentHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new QualityAgentHttpAdapter(builder.baseUrl(BASE_URL).build(), API_KEY);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsResolvedTranscriptWithDedicatedKeyAndMapsEvaluation() {
        server.expect(requestTo(BASE_URL + "/ai/quality/evaluate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-AI-Key", API_KEY))
                .andExpect(jsonPath("$.conversation_id").value(CONVERSATION_ID.toString()))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].sender").value("CUSTOMER"))
                .andExpect(jsonPath("$.messages[1].sender").value("ADVISOR"))
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.source_documents").isEmpty())
                .andExpect(jsonPath("$.applicable_rules").isEmpty())
                .andExpect(jsonPath("$.advisor_response").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "conversation_id": "%s",
                          "global_score": 8.4,
                          "scores": {
                            "relevance": 9,
                            "accuracy": 8,
                            "compliance": 8,
                            "communication": 9,
                            "empathy": 8,
                            "resolution": 8
                          },
                          "explanation": "Clear and relevant response.",
                          "flags": {
                            "unsourced_claims": 0,
                            "unverifiable_claims": 1,
                            "procedure_violations": [],
                            "missing_source_keys": []
                          },
                          "evidence": [],
                          "recommendations": [],
                          "claims": []
                        }
                        """.formatted(CONVERSATION_ID), MediaType.APPLICATION_JSON));

        var result = adapter.evaluate(request());

        assertThat(result.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(result.globalScore()).isEqualByComparingTo("8.4");
        assertThat(result.scores()).containsEntry("accuracy", 8).hasSize(6);
        assertThat(result.flags().unverifiableClaims()).isEqualTo(1);
    }

    @Test
    void translatesServerErrorsToSafeUnavailableException() {
        server.expect(requestTo(BASE_URL + "/ai/quality/evaluate")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.evaluate(request()))
                .isInstanceOf(QualityServiceUnavailableException.class)
                .hasMessage("Quality service is unavailable.");
    }

    @Test
    void translatesTimeoutsToSafeUnavailableException() {
        server.expect(requestTo(BASE_URL + "/ai/quality/evaluate"))
                .andRespond(withException(new IOException(new SocketTimeoutException("request timed out"))));

        assertThatThrownBy(() -> adapter.evaluate(request()))
                .isInstanceOf(QualityServiceUnavailableException.class)
                .hasMessage("Quality service is unavailable.");
    }

    private static QualityRequest request() {
        return new QualityRequest(
                CONVERSATION_ID,
                List.of(
                        new QualityMessage(MessageSender.CUSTOMER, "My card was charged twice."),
                        new QualityMessage(MessageSender.ADVISOR, "I have opened a dispute for the duplicate charge.")),
                true);
    }
}
