package com.callverse.internal;

import static com.callverse.auth.AuthenticatedRequests.serviceKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.conversation.ConversationFixtures;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class InternalTicketsEndpointTest extends AbstractPersistenceTest {

    private static final String PATH = "/internal/tickets";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate tx;

    private UUID customerId;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        ConversationFixtures fixtures = new ConversationFixtures(em, tx);
        String skill = fixtures.skill(60);
        customerId = fixtures.customer();
        conversationId = fixtures.conversation(
                customerId,
                skill,
                ConversationStatus.ACTIVE,
                "50.00",
                Instant.parse("2026-09-01T08:00:00Z"));
    }

    @Test
    void serviceCanOpenTicketLinkedToTheActiveConversation() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                        .with(serviceKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "customerId", customerId,
                                "conversationId", conversationId,
                                "category", "FRAUD",
                                "title", "Suspicious card transaction"))))
                .andReturn();
        String raw = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).as("body was: %s", raw).isEqualTo(201);
        JsonNode body = objectMapper.readTree(raw);
        assertThat(body.get("customerId").asText()).isEqualTo(customerId.toString());
        assertThat(body.get("conversationId").asText()).isEqualTo(conversationId.toString());
        assertThat(body.get("category").asText()).isEqualTo("FRAUD");
        assertThat(body.get("title").asText()).isEqualTo("Suspicious card transaction");
        assertThat(body.get("status").asText()).isEqualTo("OPEN");
        assertThat(body.get("severity").asInt()).isEqualTo(3);
    }
}
