package com.callverse.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A request that reaches a real route with the wrong content type is the caller's mistake: 415 in the
 * standard envelope, never the catch-all's 500. Run under {@code prod}: login is the route the chain
 * lets through anonymously, which is exactly where an examiner would try it. The 405 case lives in
 * {@link MethodNotAllowedTest}, because under {@code prod} the chain refuses a wrong method first.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProtocolErrorTest extends AbstractPersistenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("login sent as text/plain is 415 UNSUPPORTED_MEDIA_TYPE, not 500")
    void wrongContentTypeIs415() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("email=a&password=b"))
                .andReturn();

        ErrorEnvelope.assertConforms(body(result), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("health is reachable outside dev, without a token")
    void healthIsReachable() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health/status")).andReturn();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(result).get("status").asText()).isNotBlank();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
