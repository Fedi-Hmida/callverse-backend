package com.callverse.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.persistence.AbstractPersistenceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The {@code dev} profile permits every request, so what it exposes must be small. Two guarantees:
 * demo data never loads unless explicitly enabled, and the actuator does not publish a heap dump
 * (whose memory holds the JWT signing key) or the environment.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevProfileSafetyTest extends AbstractPersistenceTest {

    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("demo data is off unless callverse.demo-data.enabled=true")
    void demoLoaderIsOffByDefault() {
        assertThat(context.getBeansOfType(ApplicationRunner.class).values())
                .noneMatch(runner -> runner.getClass().getName().contains("DemoDataLoader"));
    }

    @Test
    @DisplayName("heapdump and env are not exposed in dev; health still is")
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        assertThat(mockMvc.perform(get("/actuator/heapdump")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
