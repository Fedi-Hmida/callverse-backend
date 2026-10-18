package com.callverse.infrastructure.ai;

import com.callverse.core.application.exceptions.WorkforceServiceUnavailableException;
import com.callverse.core.application.interfaces.WorkforceAgent;
import com.callverse.core.application.interfaces.WorkforceAgent.WorkforceProposal;
import com.callverse.core.application.interfaces.WorkforceAgent.WorkforceState;
import com.callverse.core.domain.enums.SkillCode;
import com.callverse.core.domain.enums.WorkforceActionType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP adapter for the Python Workforce Manager's recommendation-only endpoint. */
@Component
class WorkforceAgentHttpAdapter implements WorkforceAgent {

    private final RestClient client;
    private final String apiKey;

    @Autowired
    WorkforceAgentHttpAdapter(
            RestClient.Builder builder,
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.apiKey = apiKey;
    }

    WorkforceAgentHttpAdapter(RestClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    public WorkforceProposal decide(WorkforceState state) {
        WorkforceResponseWire response;
        try {
            response = client.post()
                    .uri("/ai/workforce/decide?strategy=PPO")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::addApiKey)
                    .body(toWire(state))
                    .retrieve()
                    .body(WorkforceResponseWire.class);
        } catch (RestClientException exception) {
            throw new WorkforceServiceUnavailableException("Workforce service is unavailable.", exception);
        }
        return toProposal(response);
    }

    private void addApiKey(HttpHeaders headers) {
        if (StringUtils.hasText(apiKey)) {
            headers.set("X-AI-Key", apiKey);
        }
    }

    private static WorkforceRequestWire toWire(WorkforceState state) {
        return new WorkforceRequestWire(
                skillMap(state.queues()),
                skillMap(state.averageWaitSeconds()),
                skillMap(state.available()),
                state.slaToday(),
                state.hour(),
                state.trend());
    }

    private static <T> Map<String, T> skillMap(Map<SkillCode, T> values) {
        return values.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> entry.getKey().name(), Map.Entry::getValue));
    }

    private static WorkforceProposal toProposal(WorkforceResponseWire response) {
        if (response == null
                || response.type() == null
                || response.reason() == null
                || response.expectedGain() == null
                || response.strategy() == null
                || !response.requiresApproval()) {
            throw new WorkforceServiceUnavailableException("Workforce service returned an invalid proposal.");
        }
        if (response.type() == WorkforceActionType.REASSIGN
                && (response.fromPool() == null || response.toPool() == null || response.count() == null)) {
            throw new WorkforceServiceUnavailableException("Workforce service returned an incomplete proposal.");
        }
        if (response.type() == WorkforceActionType.NONE
                && (response.fromPool() != null || response.toPool() != null || response.count() != null)) {
            throw new WorkforceServiceUnavailableException("Workforce service returned an inconsistent proposal.");
        }
        return new WorkforceProposal(
                response.type(),
                response.fromPool(),
                response.toPool(),
                response.count(),
                response.reason(),
                response.expectedGain(),
                response.strategy(),
                response.actionId(),
                response.requiresApproval());
    }

    private record WorkforceRequestWire(
            Map<String, Long> queues,
            @JsonProperty("avg_wait") Map<String, Double> averageWait,
            Map<String, Integer> available,
            @JsonProperty("sla_today") double slaToday,
            int hour,
            double trend) {}

    private record WorkforceResponseWire(
            WorkforceActionType type,
            @JsonProperty("from_pool") SkillCode fromPool,
            @JsonProperty("to_pool") SkillCode toPool,
            Integer count,
            String reason,
            @JsonProperty("expected_gain") Map<String, Double> expectedGain,
            String strategy,
            @JsonProperty("action_id") int actionId,
            @JsonProperty("requires_approval") boolean requiresApproval) {}
}
