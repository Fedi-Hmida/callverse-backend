package com.callverse.conversation;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The customer's side of conversations, against the deny-by-default {@code prod} chain: one live
 * conversation at a time, removal from the customer's own space (the bank keeps the record), and a
 * list headed by who handles each request.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class CustomerConversationRulesEndpointTest extends AbstractPersistenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private ConversationFixtures fx;
    private String skill;
    private UUID karimUser;
    private UUID karim;
    private UUID aminaUser;
    private UUID amina;

    @BeforeEach
    void setUp() {
        fx = new ConversationFixtures(em, tx);
        skill = fx.skill(60);
        karimUser = fx.user(UserRole.ADVISOR);
        karim = fx.advisor(karimUser, 3, skill);
        aminaUser = fx.user(UserRole.CUSTOMER);
        amina = fx.customer(aminaUser, ChurnRisk.LOW, CustomerSegment.MASS);
    }

    private static RequestPostProcessor as(UUID userId, UserRole role) {
        return bearer(validToken(userId, role.name().toLowerCase() + "@test.local", role));
    }

    private RequestPostProcessor aminaLogin() {
        return as(aminaUser, UserRole.CUSTOMER);
    }

    private RequestPostProcessor karimLogin() {
        return as(karimUser, UserRole.ADVISOR);
    }

    private JsonNode call(RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as("body was: %s", body).isEqualTo(expectedStatus);
        return body.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(body);
    }

    private RequestBuilder openOwn() {
        return post("/api/v1/me/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"skill\": \"%s\"}".formatted(skill)).with(aminaLogin());
    }

    private RequestBuilder openForCustomer(UUID customerId, RequestPostProcessor who) {
        return post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\": \"%s\", \"skill\": \"%s\"}".formatted(customerId, skill)).with(who);
    }

    private long liveConversationsOf(UUID customerId) {
        return tx.execute(s -> em.createQuery("select count(c) from Conversation c where c.customer.id = :id"
                        + " and c.runId is null and c.status in :live", Long.class)
                .setParameter("id", customerId)
                .setParameter("live", List.of(ConversationStatus.QUEUED, ConversationStatus.ASSIGNED,
                        ConversationStatus.ACTIVE, ConversationStatus.ESCALATED))
                .getSingleResult());
    }

    private Instant removedAt(UUID conversationId) {
        return tx.execute(s -> em.createQuery("select c.customerDeletedAt from Conversation c where c.id = :id", Instant.class)
                .setParameter("id", conversationId).getSingleResult());
    }

    @Nested
    @DisplayName("one live conversation at a time")
    class OneLiveConversation {

        @Test
        @DisplayName("a customer with a conversation in progress is told to finish it, with its id, and nothing is opened")
        void aSecondOpenIsRefusedWithTheConversationToResume() throws Exception {
            String first = call(openOwn(), 201).get("id").asText();

            JsonNode refused = call(openOwn(), 409);

            assertThat(refused.get("code").asText()).isEqualTo("CONVERSATION_ALREADY_OPEN");
            assertThat(refused.get("message").asText()).contains(first);
            assertThat(liveConversationsOf(amina)).isEqualTo(1);
        }

        @Test
        @DisplayName("the rule holds at the front desk too: staff cannot open a second live contact for the customer")
        void staffAreHeldToTheSameRule() throws Exception {
            call(openOwn(), 201);
            UUID desk = fx.user(UserRole.ADVISOR);
            fx.advisor(desk, 1, skill);

            JsonNode refused = call(openForCustomer(amina, as(desk, UserRole.ADVISOR)), 409);

            assertThat(refused.get("code").asText()).isEqualTo("CONVERSATION_ALREADY_OPEN");
            assertThat(liveConversationsOf(amina)).isEqualTo(1);
        }

        @Test
        @DisplayName("once the conversation is closed, a new one opens normally")
        void aClosedConversationNoLongerBlocks() throws Exception {
            String first = call(openOwn(), 201).get("id").asText();
            call(post("/api/v1/conversations/{id}/abandon", first).with(aminaLogin()), 200);

            call(openOwn(), 201);

            assertThat(liveConversationsOf(amina)).isEqualTo(1);
        }

        @Test
        @DisplayName("two opens at the same instant leave exactly one live conversation")
        void concurrentOpensCannotBothSucceed() throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Callable<Integer> attempt = () -> {
                        start.await();
                        return mockMvc.perform(openOwn()).andReturn().getResponse().getStatus();
                    };
                    results.add(pool.submit(attempt));
                }
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> result : results) {
                    statuses.add(result.get());
                }
                assertThat(statuses).containsOnly(201, 409);
                assertThat(statuses.stream().filter(code -> code == 201)).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(liveConversationsOf(amina)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("removing a conversation from the customer's space")
    class Removal {

        @Test
        @DisplayName("removing a conversation in service ends it, keeps the record with a notice, and hides it from the customer only")
        void removingAnAssignedConversation() throws Exception {
            UUID conversation = fx.conversation(amina, skill, ConversationStatus.ASSIGNED, "50.00",
                    Instant.parse("2026-10-18T09:00:00Z"), karim, null);
            fx.message(conversation, com.callverse.core.domain.enums.MessageSender.CUSTOMER, "Ma carte est refusée",
                    Instant.parse("2026-10-18T09:00:20Z"));

            call(delete("/api/v1/me/conversations/{id}", conversation).with(aminaLogin()), 204);

            assertThat(fx.reload(conversation).getStatus()).isEqualTo(ConversationStatus.ABANDONED);
            assertThat(removedAt(conversation)).isNotNull();
            call(get("/api/v1/conversations/{id}", conversation).with(aminaLogin()), 404);
            call(get("/api/v1/conversations/{id}/messages", conversation).with(aminaLogin()), 404);

            JsonNode transcript = call(get("/api/v1/conversations/{id}/messages", conversation)
                    .with(as(UUID.randomUUID(), UserRole.SUPERVISOR)), 200).get("messages");
            JsonNode last = transcript.get(transcript.size() - 1);
            assertThat(last.get("sender").asText()).isEqualTo("SYSTEM");
            assertThat(last.get("content").asText()).isEqualTo("Le client a supprimé cette conversation de son espace.");
            assertThat(transcript.get(0).get("content").asText()).isEqualTo("Ma carte est refusée");

            JsonNode mine = call(get("/api/v1/conversations/accessible").with(aminaLogin()), 200).get("content");
            assertThat(mine).isEmpty();
            JsonNode supervised = call(get("/api/v1/conversations/accessible")
                    .with(as(UUID.randomUUID(), UserRole.SUPERVISOR)), 200).get("content");
            JsonNode row = find(supervised, conversation);
            assertThat(row.get("removedByCustomer").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("a closed conversation is only hidden: its status does not change")
        void removingAResolvedConversationKeepsItsStatus() throws Exception {
            UUID conversation = fx.conversation(amina, skill, ConversationStatus.RESOLVED, "50.00",
                    Instant.parse("2026-10-18T08:00:00Z"), karim, null);
            fx.ended(conversation, Instant.parse("2026-10-18T08:10:00Z"));

            call(delete("/api/v1/me/conversations/{id}", conversation).with(aminaLogin()), 204);

            assertThat(fx.reload(conversation).getStatus()).isEqualTo(ConversationStatus.RESOLVED);
            assertThat(removedAt(conversation)).isNotNull();
        }

        @Test
        @DisplayName("a conversation a supervisor is handling cannot be removed until it is closed")
        void anEscalatedConversationIsKept() throws Exception {
            UUID conversation = fx.conversation(amina, skill, ConversationStatus.ESCALATED, "50.00",
                    Instant.parse("2026-10-18T09:00:00Z"), karim, null);

            JsonNode refused = call(delete("/api/v1/me/conversations/{id}", conversation).with(aminaLogin()), 409);

            assertThat(refused.get("code").asText()).isEqualTo("CONVERSATION_UNDER_SUPERVISION");
            assertThat(fx.reload(conversation).getStatus()).isEqualTo(ConversationStatus.ESCALATED);
            assertThat(removedAt(conversation)).isNull();
        }

        @Test
        @DisplayName("another customer's conversation, or one already removed, does not exist for the caller")
        void othersAndAlreadyRemovedAreNotFound() throws Exception {
            UUID otherUser = fx.user(UserRole.CUSTOMER);
            fx.customer(otherUser, ChurnRisk.LOW, CustomerSegment.MASS);
            UUID conversation = fx.conversation(amina, skill, ConversationStatus.QUEUED, "50.00",
                    Instant.parse("2026-10-18T09:00:00Z"));

            call(delete("/api/v1/me/conversations/{id}", conversation).with(as(otherUser, UserRole.CUSTOMER)), 404);
            assertThat(removedAt(conversation)).isNull();

            call(delete("/api/v1/me/conversations/{id}", conversation).with(aminaLogin()), 204);
            call(delete("/api/v1/me/conversations/{id}", conversation).with(aminaLogin()), 404);
        }

        @Test
        @DisplayName("staff cannot use the customer's removal")
        void staffAreRefused() throws Exception {
            UUID conversation = fx.conversation(amina, skill, ConversationStatus.ASSIGNED, "50.00",
                    Instant.parse("2026-10-18T09:00:00Z"), karim, null);

            call(delete("/api/v1/me/conversations/{id}", conversation).with(karimLogin()), 403);

            assertThat(removedAt(conversation)).isNull();
        }

        @Test
        @DisplayName("after removing the conversation in progress, the customer can open a new one")
        void removalFreesTheCustomerToOpenAgain() throws Exception {
            String first = call(openOwn(), 201).get("id").asText();
            call(openOwn(), 409);

            call(delete("/api/v1/me/conversations/{id}", first).with(aminaLogin()), 204);

            call(openOwn(), 201);
        }
    }

    @Nested
    @DisplayName("the conversation list says who handles each request")
    class ListHeadings {

        @Test
        @DisplayName("a customer sees the advisor and the subject, not their own name; staff see the customer")
        void counterpartAndSubjectDependOnTheCaller() throws Exception {
            UUID assigned = fx.conversation(amina, skill, ConversationStatus.ASSIGNED, "50.00",
                    Instant.parse("2026-10-18T09:00:00Z"), karim, null);
            String advisorName = tx.execute(s -> em.find(com.callverse.core.domain.entities.Advisor.class, karim)
                    .getDisplayName());

            JsonNode mine = call(get("/api/v1/conversations/accessible").with(aminaLogin()), 200).get("content");
            JsonNode row = find(mine, assigned);
            assertThat(row.get("counterpartName").asText()).isEqualTo(advisorName);
            assertThat(row.get("subject").asText()).isEqualTo("Test skill");
            assertThat(row.get("removedByCustomer").asBoolean()).isFalse();

            JsonNode karimView = call(get("/api/v1/conversations/accessible").with(karimLogin()), 200).get("content");
            assertThat(find(karimView, assigned).get("counterpartName").asText()).isEqualTo("Amina Haddad");
        }

        @Test
        @DisplayName("a waiting request has no counterpart yet: the customer's list says so with null, never their own name")
        void aQueuedRequestHasNoCounterpartForTheCustomer() throws Exception {
            UUID queued = fx.conversation(amina, skill, ConversationStatus.QUEUED, "50.00",
                    Instant.parse("2026-10-18T09:00:00Z"));

            JsonNode row = find(call(get("/api/v1/conversations/accessible").with(aminaLogin()), 200).get("content"), queued);

            assertThat(row.get("counterpartName").isNull()).isTrue();
        }
    }

    private static JsonNode find(JsonNode rows, UUID id) {
        for (JsonNode row : rows) {
            if (row.get("id").asText().equals(id.toString())) {
                return row;
            }
        }
        throw new AssertionError("conversation " + id + " not in " + rows);
    }
}
