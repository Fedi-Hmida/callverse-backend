package com.callverse.infrastructure.ai;

import com.callverse.core.application.interfaces.AdvisorAgent;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorRequest;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorResponse;
import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.application.interfaces.AdvisorAgent.Source;
import com.callverse.core.application.interfaces.AdvisorAgent.ToolCall;
import com.callverse.core.domain.enums.MessageSender;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** HTTP adapter for the Python Customer Advisor. */
@Component
class AdvisorAgentHttpAdapter implements AdvisorAgent {

    private final RestClient client;
    private final String apiKey;

    @Autowired
    AdvisorAgentHttpAdapter(
            RestClient.Builder builder,
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.apiKey = apiKey;
    }

    AdvisorAgentHttpAdapter(RestClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    public AdvisorResponse respond(AdvisorRequest request) {
        AdvisorResponseWire response = client.post()
                .uri("/ai/advisor/respond")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> addApiKey(headers))
                .body(toWire(request))
                .retrieve()
                .body(AdvisorResponseWire.class);
        if (response == null) {
            throw new IllegalStateException("AI service returned an empty Advisor response");
        }
        return response.toApplicationResponse();
    }

    private void addApiKey(HttpHeaders headers) {
        if (StringUtils.hasText(apiKey)) {
            headers.set("X-AI-Key", apiKey);
        }
    }

    private static AdvisorRequestWire toWire(AdvisorRequest request) {
        return new AdvisorRequestWire(
                request.conversationId(),
                request.customerId(),
                request.message(),
                request.history().stream().map(AdvisorAgentHttpAdapter::toWire).toList(),
                new AdvisorContextWire(request.context().tenureMonths(), request.context().segment().name()),
                request.mode().name());
    }

    private static HistoryMessageWire toWire(HistoryMessage message) {
        return new HistoryMessageWire(
                message.role() == MessageSender.CUSTOMER ? "customer" : "advisor",
                message.content(),
                message.timestamp());
    }

    private record AdvisorRequestWire(
            @JsonProperty("conversation_id") UUID conversationId,
            @JsonProperty("customer_id") UUID customerId,
            String message,
            List<HistoryMessageWire> history,
            AdvisorContextWire context,
            String mode) {}

    private record HistoryMessageWire(String role, String content, @JsonProperty("ts") Instant timestamp) {}

    private record AdvisorContextWire(
            @JsonProperty("tenure_months") int tenureMonths, String segment) {}

    private record AdvisorResponseWire(
            String reply,
            String intent,
            double confidence,
            @JsonProperty("tool_calls") List<ToolCallWire> toolCalls,
            List<SourceWire> sources,
            ActionWire action,
            @JsonProperty("latency_ms") long latencyMs) {

        AdvisorResponse toApplicationResponse() {
            if (toolCalls == null || sources == null || action == null) {
                throw new IllegalStateException("AI service returned an incomplete Advisor response");
            }
            return new AdvisorResponse(
                    reply,
                    intent,
                    confidence,
                    toolCalls.stream().map(ToolCallWire::toApplicationToolCall).toList(),
                    sources.stream().map(SourceWire::toApplicationSource).toList(),
                    action.type(),
                    action.payload(),
                    latencyMs);
        }
    }

    private record ToolCallWire(
            String tool,
            Map<String, Object> args,
            @JsonProperty("result_summary") String resultSummary,
            boolean ok) {

        ToolCall toApplicationToolCall() {
            return new ToolCall(tool, args, resultSummary, ok);
        }
    }

    private record SourceWire(
            @JsonProperty("kb_article_id") UUID knowledgeBaseArticleId, double score) {

        Source toApplicationSource() {
            return new Source(knowledgeBaseArticleId, score);
        }
    }

    private record ActionWire(String type, Map<String, Object> payload) {}
}
