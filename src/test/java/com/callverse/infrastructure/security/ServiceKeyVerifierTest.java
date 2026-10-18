package com.callverse.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The service-key comparison and its startup guard, with no Spring context. */
class ServiceKeyVerifierTest {

    private static final String KEY = "a-service-key-of-at-least-thirty-two-bytes-0123";

    private final ServiceKeyVerifier verifier = new ServiceKeyVerifier(KEY);

    @Test
    @DisplayName("a key shorter than 32 bytes fails at construction, not at the first call")
    void shortKeyFailsAtStartup() {
        assertThatThrownBy(() -> new ServiceKeyVerifier("only-31-bytes-long-000000000000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageNotContaining("only-31-bytes");
    }

    @Test
    @DisplayName("the configured key matches")
    void configuredKeyMatches() {
        assertThat(verifier.matches(KEY)).isTrue();
    }

    @Test
    @DisplayName("anything else does not: wrong, one byte off, a prefix, longer, empty, absent")
    void everythingElseIsRefused() {
        assertThat(verifier.matches("wrong")).isFalse();
        assertThat(verifier.matches(KEY.substring(0, KEY.length() - 1) + "4")).isFalse();
        assertThat(verifier.matches(KEY.substring(0, 20))).isFalse();
        assertThat(verifier.matches(KEY + "0")).isFalse();
        assertThat(verifier.matches("")).isFalse();
        assertThat(verifier.matches(null)).isFalse();
    }
}
