package com.callverse.internal;

import static com.callverse.auth.AuthenticatedRequests.serviceKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class InternalCreditEndpointTest extends AbstractPersistenceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void incompleteCreditRequestIsRejectedBeforeAnyBooking() throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/credits")
                        .with(serviceKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        String raw = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).as("body was: %s", raw).isEqualTo(400);
        JsonNode body = objectMapper.readTree(raw);
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path");
    }
}
