package com.callverse.infrastructure.ai;

import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.application.interfaces.SimulatorAgent;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorRequest;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorResponse;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorState;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP adapter for the Python Client Simulator. */
@Component
class SimulatorAgentHttpAdapter implements SimulatorAgent {

    private final RestClient client;
    private final String apiKey;

    @Autowired
    SimulatorAgentHttpAdapter(
            RestClient.Builder builder,
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.apiKey = apiKey;
    }

    SimulatorAgentHttpAdapter(RestClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    public SimulatorResponse nextMessage(SimulatorRequest request) {
        SimulatorResponseWire response;
        try {
            response = client.post()
                    .uri("/ai/simulator/next-message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::addApiKey)
                    .body(toWire(request))
                    .retrieve()
                    .body(SimulatorResponseWire.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Client Simulator is unavailable.", exception);
        }
        return toResponse(response);
    }

    private void addApiKey(HttpHeaders headers) {
        if (StringUtils.hasText(apiKey)) {
            headers.set("X-AI-Key", apiKey);
        }
    }

    private static SimulatorRequestWire toWire(SimulatorRequest request) {
        return new SimulatorRequestWire(
                request.conversationId(),
                request.history().stream().map(SimulatorAgentHttpAdapter::toWire).toList(),
                request.lastAdvisorReply(),
                request.waitSeconds(),
                toWire(request.state()));
    }

    private static HistoryMessageWire toWire(HistoryMessage message) {
        return new HistoryMessageWire(
                message.role().name().toLowerCase(Locale.ROOT), message.content(), message.timestamp());
    }

    private static SimulatorStateWire toWire(SimulatorState state) {
        return new SimulatorStateWire(
                state.profile().name().toLowerCase(Locale.ROOT),
                state.patience(),
                state.satisfaction(),
                state.objective(),
                state.objectiveMet());
    }

    private static SimulatorResponse toResponse(SimulatorResponseWire response) {
        if (response == null || response.status() == null || response.state() == null) {
            throw new IllegalStateException("Client Simulator returned an incomplete response.");
        }
        SimulatorState state;
        try {
            state = new SimulatorState(
                    com.callverse.core.domain.enums.SimulatorProfile.valueOf(response.state().profile().toUpperCase(Locale.ROOT)),
                    response.state().patience(),
                    response.state().satisfaction(),
                    response.state().objective(),
                    response.state().objectiveMet());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Client Simulator returned an invalid state.", exception);
        }
        SimulatorAgent.Outcome outcome = switch (response.status()) {
            case "en_cours" -> SimulatorAgent.Outcome.IN_PROGRESS;
            case "resolu" -> SimulatorAgent.Outcome.RESOLVED;
            case "abandonne" -> SimulatorAgent.Outcome.ABANDONED;
            default -> throw new IllegalStateException("Client Simulator returned an invalid status.");
        };
        return new SimulatorResponse(response.content(), outcome, state);
    }

    private record SimulatorRequestWire(
            @JsonProperty("conversation_id") UUID conversationId,
            List<HistoryMessageWire> history,
            @JsonProperty("last_advisor_reply") String lastAdvisorReply,
            @JsonProperty("wait_seconds") int waitSeconds,
            SimulatorStateWire state) {}

    private record HistoryMessageWire(String role, String content, @JsonProperty("ts") Instant timestamp) {}

    private record SimulatorStateWire(
            String profile,
            double patience,
            double satisfaction,
            String objective,
            @JsonProperty("objective_met") boolean objectiveMet) {}

    private record SimulatorResponseWire(
            String content, String status, SimulatorStateWire state) {}
}
