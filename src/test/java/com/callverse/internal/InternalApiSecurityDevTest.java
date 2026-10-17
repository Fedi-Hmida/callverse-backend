package com.callverse.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code /internal} under {@code dev}, where every user route is {@code permitAll}. This is the
 * profile where a missing key check would pass unnoticed, so it is the one that matters most.
 */
@ActiveProfiles("dev")
class InternalApiSecurityDevTest extends AbstractInternalApiSecurityTest {

    @Test
    @DisplayName("the dev chain stays open for user routes: the internal chain changes nothing there")
    void userRoutesStayOpenUnderDev() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/health/status")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }
}
