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
class InternalEscalationEndpointTest extends AbstractPersistenceTest {

    private static final String PATH = "/internal/conversations/{id}/escalate";
    private static final String REASON = "Suspected fraud needs supervisor review";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate tx;

    private ConversationFixtures fixtures;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        fixtures = new ConversationFixtures(em, tx);
        String skill = fixtures.skill(60);
        UUID customerId = fixtures.customer();
        conversationId = fixtures.conversation(
                customerId,
                skill,
                ConversationStatus.ACTIVE,
                "50.00",
                Instant.parse("2026-09-01T08:00:00Z"));
    }

    @Test
    void serviceEscalationMovesConversationAndIsIdempotent() throws Exception {
        MvcResult created = request();
        String createdBody = created.getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(createdBody);

        assertThat(created.getResponse().getStatus()).as("body was: %s", createdBody).isEqualTo(201);
        assertThat(first.get("conversationId").asText()).isEqualTo(conversationId.toString());
        assertThat(first.get("reason").asText()).isEqualTo(REASON);
        assertThat(first.get("raisedBy").asText()).isEqualTo("AI");
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(fixtures.reload(conversationId).getStatus()).isEqualTo(ConversationStatus.ESCALATED);

        MvcResult retried = request();
        String retriedBody = retried.getResponse().getContentAsString();
        JsonNode second = objectMapper.readTree(retriedBody);

        assertThat(retried.getResponse().getStatus()).as("body was: %s", retriedBody).isEqualTo(200);
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
    }

    private MvcResult request() throws Exception {
        return mockMvc.perform(post(PATH, conversationId)
                        .with(serviceKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("reason", REASON))))
                .andReturn();
    }
}
