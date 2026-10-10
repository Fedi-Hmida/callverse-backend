package com.callverse.conversation;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The supervisor's conversation list over HTTP, on the {@code prod} chain: a supervisor finds the
 * chats of a customer and opens one; advisors and customers are refused; every bad parameter is 400.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class SupervisionConversationEndpointTest extends AbstractPersistenceTest {

    private static final String LIST = "/api/v1/supervision/conversations";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private ConversationFixtures fx;
    private String skill;
    private UUID amina;
    private UUID escalated;
    private UUID resolved;

    @BeforeEach
    void setUp() {
        fx = new ConversationFixtures(em, tx);
        skill = fx.skill(60);
        amina = fx.namedCustomer("Amina", "Haddad");
        UUID karim = fx.advisor(fx.user(UserRole.ADVISOR), 2, skill);
        Instant t0 = Instant.now().minusSeconds(3600);
        resolved = fx.conversation(amina, skill, ConversationStatus.RESOLVED, "0", t0, karim, null);
        escalated = fx.conversation(amina, skill, ConversationStatus.ESCALATED, "0", t0.plusSeconds(600), karim, null);
        fx.message(escalated, MessageSender.CUSTOMER, "Je n'ai pas fait ces paiements", t0.plusSeconds(700));
        fx.pendingEscalation(escalated);
    }

    private static RequestPostProcessor as(UserRole role) {
        return bearer(validToken(UUID.randomUUID(), role.name().toLowerCase() + "@test.local", role));
    }

    private JsonNode call(RequestBuilder request, int expected) throws Exception {
        MvcResult r = mockMvc.perform(request).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as("body was: %s", body).isEqualTo(expected);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    @Test
    @DisplayName("a supervisor lists a customer's chats, newest first, then opens one with the existing routes")
    void supervisorBrowsesAndOpens() throws Exception {
        JsonNode page = call(get(LIST).param("customerId", amina.toString()).with(as(UserRole.SUPERVISOR)), 200);

        assertThat(page.get("page").get("totalElements").asLong()).isEqualTo(2);
        JsonNode first = page.get("content").get(0);
        assertThat(first.get("id").asText()).isEqualTo(escalated.toString());
        assertThat(first.get("status").asText()).isEqualTo("ESCALATED");
        assertThat(first.get("customer").get("name").asText()).isEqualTo("Amina Haddad");
        assertThat(first.get("customer").get("reference").asText()).isEqualTo(fx.reference(amina));
        assertThat(first.get("advisor").get("name").asText()).startsWith("Karim");
        assertThat(first.get("messageCount").asLong()).isEqualTo(1);
        assertThat(first.get("pendingEscalation").asBoolean()).isTrue();
        assertThat(page.get("content").get(1).get("id").asText()).isEqualTo(resolved.toString());

        JsonNode transcript = call(get("/api/v1/conversations/{id}/messages", escalated).with(as(UserRole.SUPERVISOR)), 200);
        assertThat(transcript.get("messages").get(0).get("content").asText()).isEqualTo("Je n'ai pas fait ces paiements");
    }

    @Test
    @DisplayName("several statuses at once, and a text search on the customer's name")
    void filters() throws Exception {
        JsonNode open = call(get(LIST).param("customerId", amina.toString())
                .param("status", "ACTIVE").param("status", "ESCALATED").with(as(UserRole.SUPERVISOR)), 200);
        assertThat(open.get("content")).hasSize(1);

        JsonNode byName = call(get(LIST).param("q", "amina haddad").param("skill", skill)
                .with(as(UserRole.SUPERVISOR)), 200);
        assertThat(byName.get("page").get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("supervisors and admins only: advisors and customers 403, anonymous 401")
    void whoMay() throws Exception {
        call(get(LIST).with(as(UserRole.ADMIN)), 200);
        ErrorEnvelope.assertConforms(call(get(LIST).with(as(UserRole.ADVISOR)), 403), 403, "ACCESS_DENIED");
        call(get(LIST).with(as(UserRole.CUSTOMER)), 403);
        call(get(LIST), 401);
    }

    @Test
    @DisplayName("bad parameters are 400 VALIDATION_FAILED: size, page, status, id, dates, text")
    void validation() throws Exception {
        RequestPostProcessor sarah = as(UserRole.SUPERVISOR);
        call(get(LIST).param("size", "100").with(sarah), 200);
        ErrorEnvelope.assertConforms(call(get(LIST).param("size", "101").with(sarah), 400), 400, "VALIDATION_FAILED");
        call(get(LIST).param("page", "-1").with(sarah), 400);
        call(get(LIST).param("status", "BOGUS").with(sarah), 400);
        call(get(LIST).param("customerId", "not-a-uuid").with(sarah), 400);
        call(get(LIST).param("from", "2026-10-10T10:00:00Z").param("to", "2026-10-10T09:00:00Z").with(sarah), 400);
        call(get(LIST).param("q", "x".repeat(101)).with(sarah), 400);
    }
}
