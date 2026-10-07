package com.callverse.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A wrong HTTP method on a real route is 405 in the standard envelope, with an {@code Allow} header,
 * never the catch-all's 500. Under {@code dev}, where the chain lets every request reach MVC; under
 * {@code prod} the chain answers first, so this is the only profile where MVC's answer is observable.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MethodNotAllowedTest extends AbstractPersistenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("DELETE on the login route is 405 METHOD_NOT_ALLOWED and names the allowed method")
    void wrongMethodIs405() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/auth/login")).andReturn();

        ErrorEnvelope.assertConforms(
                objectMapper.readTree(result.getResponse().getContentAsString()), 405, "METHOD_NOT_ALLOWED");
        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("POST");
    }
}
