package com.callverse.internal;

import static com.callverse.auth.AuthenticatedRequests.serviceKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for the {@code /internal} tool tests: every call is made as the AI service, with the key.
 *
 * <p>{@code dev} profile, so the user chain is {@code permitAll} and a passing call proves only that
 * the tool works — the key itself is asserted in {@code AbstractInternalApiSecurityTest}. Each test
 * runs in a transaction that rolls its fixtures back.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
abstract class AbstractInternalToolTest extends AbstractPersistenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager em;

    InternalFixtures fixtures;

    @BeforeEach
    void createFixtures() {
        fixtures = new InternalFixtures(em);
    }

    JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return call(request, 200);
    }

    JsonNode call(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request.with(serviceKey())).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("body: %s", result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }
}
