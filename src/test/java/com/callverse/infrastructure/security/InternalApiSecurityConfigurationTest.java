package com.callverse.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InternalApiSecurityConfigurationTest {

    @Test
    void rejectsUnsafeInternalApiPrefixes() {
        for (String prefix : new String[] {"", "/", "/api", "/api/v1", "internal", "/internal/"}) {
            assertThatThrownBy(() -> InternalApiSecurityConfiguration.requireSafePrefix(prefix))
                    .as("prefix %s", prefix)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("internal.api-prefix");
        }
    }

    @Test
    void acceptsSafeInternalApiPrefixes() {
        for (String prefix : new String[] {"/internal", "/api/v1/internal"}) {
            assertThat(InternalApiSecurityConfiguration.requireSafePrefix(prefix)).isEqualTo(prefix);
        }
    }
}
