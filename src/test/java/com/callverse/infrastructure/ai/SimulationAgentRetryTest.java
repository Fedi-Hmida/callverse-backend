package com.callverse.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class SimulationAgentRetryTest {
    private static RestClientResponseException http(int status) {
        return new RestClientResponseException("safe error", status, "error", HttpHeaders.EMPTY, null, null);
    }

    @Test void recoversFromWrappedProviderQuotaWithoutRepeatingCompletedTurns() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> pauses = new ArrayList<>();
        String response = SimulationAgentRetry.call(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("Simulator unavailable", http(429));
            return "completed turn";
        }, pauses::add);
        assertThat(response).isEqualTo("completed turn");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(pauses).containsExactly(Duration.ofSeconds(5));
    }

    @Test void capsRetriesAndWaitsForPersistentOutages() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> pauses = new ArrayList<>();
        assertThatThrownBy(() -> SimulationAgentRetry.call(() -> {
            calls.incrementAndGet(); throw http(503);
        }, pauses::add)).isInstanceOf(RestClientResponseException.class);
        assertThat(calls.get()).isEqualTo(4);
        assertThat(pauses).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));
    }

    @Test void doesNotRetryAuthorizationOrInvalidOutput() {
        for (RuntimeException failure : List.of(http(401), http(403), new IllegalArgumentException("Invalid response"))) {
            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> SimulationAgentRetry.call(() -> {
                calls.incrementAndGet(); throw failure;
            }, ignored -> { throw new AssertionError("Must not wait"); })).isSameAs(failure);
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test void respectsProviderRetryAfterAcrossWrappedFailures() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "17");
        RuntimeException quota = new IllegalStateException("Unavailable", new RestClientResponseException(
                "safe", 429, "limited", headers, null, null));
        AtomicInteger calls = new AtomicInteger();
        List<Duration> pauses = new ArrayList<>();
        String result = SimulationAgentRetry.call(() -> {
            if (calls.incrementAndGet() == 1) throw quota;
            return "ok";
        }, pauses::add);
        assertThat(result).isEqualTo("ok");
        assertThat(pauses).containsExactly(Duration.ofSeconds(17));
    }

    @Test void doesNotHoldAWorkerForDailyQuotaExhaustion() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "3600");
        RuntimeException quota = new RestClientResponseException("safe", 429, "limited", headers, null, null);
        assertThatThrownBy(() -> SimulationAgentRetry.call(() -> { throw quota; },
                ignored -> { throw new AssertionError("Must not wait an hour"); })).isSameAs(quota);
    }
}
