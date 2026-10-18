package com.callverse.infrastructure.ai;

import com.callverse.core.application.interfaces.TwinSimulator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls {@code POST /ai/workforce/twin-run}. An eight-hour simulated day takes well under a second,
 * so the call is synchronous with the usual 30-second read timeout. Any transport error, missing
 * field or out-of-range rate is a failure: a twin run is only stored when its KPIs are plausible.
 */
@Component
class TwinSimulatorHttpAdapter implements TwinSimulator {

    private final RestClient client;
    private final String apiKey;

    @Autowired
    TwinSimulatorHttpAdapter(
            RestClient.Builder builder,
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.apiKey = apiKey;
    }

    TwinSimulatorHttpAdapter(RestClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    public TwinKpis run(TwinRunSpec spec) {
        TwinRunResultWire response;
        try {
            response = client.post()
                    .uri("/ai/workforce/twin-run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::addApiKey)
                    .body(new TwinRunRequestWire(spec.strategy(), spec.seed(), spec.load().name(),
                            spec.durationMinutes(), spec.advisorsPerSkill()))
                    .retrieve()
                    .body(TwinRunResultWire.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Digital twin service is unavailable.", exception);
        }
        return toKpis(response);
    }

    private void addApiKey(HttpHeaders headers) {
        if (StringUtils.hasText(apiKey)) {
            headers.set("X-AI-Key", apiKey);
        }
    }

    private static TwinKpis toKpis(TwinRunResultWire response) {
        if (response == null
                || response.arrived() == null || response.served() == null
                || response.slaRate() == null || response.slaAllArrivals() == null
                || response.abandonRate() == null || response.avgWaitSeconds() == null
                || response.p95WaitSeconds() == null || response.reassignments() == null) {
            throw new IllegalStateException("Digital twin returned an incomplete result.");
        }
        if (!rate(response.slaRate()) || !rate(response.slaAllArrivals()) || !rate(response.abandonRate())
                || response.avgWaitSeconds() < 0 || response.p95WaitSeconds() < 0
                || response.served() > response.arrived()) {
            throw new IllegalStateException("Digital twin returned implausible KPIs.");
        }
        return new TwinKpis(response.arrived(), response.served(), response.slaRate(), response.slaAllArrivals(),
                response.abandonRate(), response.avgWaitSeconds(), response.p95WaitSeconds(),
                response.reassignments());
    }

    private static boolean rate(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private record TwinRunRequestWire(
            String strategy,
            long seed,
            @JsonProperty("load_profile") String loadProfile,
            @JsonProperty("duration_minutes") int durationMinutes,
            @JsonProperty("advisors_per_skill") int advisorsPerSkill) {}

    private record TwinRunResultWire(
            Integer arrived,
            Integer served,
            @JsonProperty("sla_rate") Double slaRate,
            @JsonProperty("sla_all_arrivals") Double slaAllArrivals,
            @JsonProperty("abandon_rate") Double abandonRate,
            @JsonProperty("avg_wait_seconds") Double avgWaitSeconds,
            @JsonProperty("p95_wait_seconds") Double p95WaitSeconds,
            Integer reassignments) {}
}
