package com.callverse.infrastructure.ai;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Bounded retries for isolated simulations only: their banking tools cannot mutate live data. */
public final class SimulationAgentRetry {
    private SimulationAgentRetry() {}

    public static <T> T call(Supplier<T> operation) {
        return call(operation, duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Simulation retry interrupted.", interrupted);
            }
        });
    }

    static <T> T call(Supplier<T> operation, Consumer<Duration> pause) {
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException failure) {
                if (attempt >= 3 || !transientFailure(failure)) {
                    throw failure;
                }
                System.getLogger(SimulationAgentRetry.class.getName()).log(
                        System.Logger.Level.WARNING, "Temporary simulation agent failure; retry {0}/3", attempt + 1);
                Duration delay = retryDelay(failure, attempt);
                // A daily quota cannot be solved by holding an executor thread for hours.
                if (delay.compareTo(Duration.ofSeconds(60)) > 0) throw failure;
                pause.accept(delay);
            }
        }
    }

    private static boolean transientFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
            }
            if (cause instanceof ResourceAccessException) return true;
        }
        return false;
    }

    private static Duration retryDelay(Throwable failure, int attempt) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response && response.getResponseHeaders() != null) {
                String value = response.getResponseHeaders().getFirst("Retry-After");
                if (value == null) continue;
                try {
                    double seconds = Double.parseDouble(value);
                    if (Double.isFinite(seconds) && seconds >= 0) {
                        return Duration.ofSeconds(Math.max(1, (long) Math.ceil(seconds)));
                    }
                } catch (NumberFormatException ignored) {
                    try {
                        Instant when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                        return Duration.ofSeconds(Math.max(1, Duration.between(Instant.now(), when).getSeconds() + 1));
                    } catch (RuntimeException malformedHeader) {
                        // Fall back to the bounded exponential delay for an invalid header.
                    }
                }
            }
        }
        return Duration.ofSeconds(5L << attempt);
    }
}
