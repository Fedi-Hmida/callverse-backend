package com.callverse.errors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Input the framework cannot even bind is the caller's mistake: 400 in the envelope, never 500.
 *
 * <p>The {@code /internal} tools (withdrawn on 2026-09-30) were the first routes to take path
 * variables and query parameters; {@code GET /api/v1/customers/{id}} takes one today, and every
 * route that re-exposes those use cases will too. Left unmapped, each would reach the catch-all and
 * be reported — to the caller and in the ERROR log — as a server failure.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MalformedInputTest.TypedProbe.class)
class MalformedInputTest extends AbstractPersistenceTest {

    @RestController
    static class TypedProbe {
        @GetMapping("/test-only/typed/{id}")
        String typed(@PathVariable UUID id, @RequestParam int n) {
            return id + ":" + n;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("a path variable that is not a UUID is 400 VALIDATION_FAILED")
    void malformedUuidIsABadRequest() throws Exception {
        ErrorEnvelope.assertConforms(call(get("/test-only/typed/not-a-uuid").param("n", "3")), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a query parameter of the wrong type is 400 VALIDATION_FAILED")
    void wrongTypeParameterIsABadRequest() throws Exception {
        ErrorEnvelope.assertConforms(
                call(get("/test-only/typed/" + UUID.randomUUID()).param("n", "three")), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a missing required query parameter is 400 VALIDATION_FAILED")
    void missingParameterIsABadRequest() throws Exception {
        ErrorEnvelope.assertConforms(call(get("/test-only/typed/" + UUID.randomUUID())), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a JSON body that does not parse is 400 MALFORMED_REQUEST, on a real endpoint")
    void unparseableBodyIsABadRequest() throws Exception {
        ErrorEnvelope.assertConforms(
                call(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\": ")),
                400,
                "MALFORMED_REQUEST");
    }

    private JsonNode call(RequestBuilder request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request).andReturn().getResponse().getContentAsString());
    }
}
