package com.callverse.conversation;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
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
 * The conversation core over HTTP, against the deny-by-default {@code prod} chain: every route's
 * success next to its refusals, every write re-read from the database, and no internal field in any
 * response body.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ConversationEndpointTest extends AbstractPersistenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private ConversationFixtures fx;
    private String skill;
    private UUID karimUser;
    private UUID linaUser;
    private UUID aminaUser;
    private UUID amina;

    @BeforeEach
    void setUp() {
        fx = new ConversationFixtures(em, tx);
        skill = fx.skill(60);
        karimUser = fx.user(UserRole.ADVISOR);
        fx.advisor(karimUser, 1, skill);
        linaUser = fx.user(UserRole.ADVISOR);
        fx.advisor(linaUser, 1, skill);
        aminaUser = fx.user(UserRole.CUSTOMER);
        amina = fx.customer(aminaUser, ChurnRisk.MEDIUM, CustomerSegment.AFFLUENT);
    }

    // ------------------------------------------------------------------ plumbing

    private static RequestPostProcessor as(UUID userId, UserRole role) {
        return bearer(validToken(userId, role.name().toLowerCase() + "@test.local", role));
    }

    private static RequestPostProcessor as(UserRole role) {
        return as(UUID.randomUUID(), role);
    }

    private RequestPostProcessor karim() {
        return as(karimUser, UserRole.ADVISOR);
    }

    private RequestPostProcessor lina() {
        return as(linaUser, UserRole.ADVISOR);
    }

    private RequestPostProcessor aminaLogin() {
        return as(aminaUser, UserRole.CUSTOMER);
    }

    private JsonNode call(RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as("body was: %s", body).isEqualTo(expectedStatus);
        return body.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(body);
    }

    private RequestBuilder open(UUID customerId, String skillCode, String intent, RequestPostProcessor who) {
        String json = "{\"customerId\": \"%s\", \"skill\": \"%s\"%s}".formatted(customerId, skillCode,
                intent == null ? "" : ", \"intent\": \"" + intent + "\"");
        return post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON).content(json).with(who);
    }

    private RequestBuilder message(UUID conversationId, String content, RequestPostProcessor who) throws Exception {
        return post("/api/v1/conversations/{id}/messages", conversationId).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("content", content))).with(who);
    }

    private static void assertNoInternalFields(JsonNode conversation) {
        assertThat(conversation.has("priorityScore")).isFalse();
        assertThat(conversation.has("waitSeconds")).isFalse();
        assertThat(conversation.has("handleSeconds")).isFalse();
        assertThat(conversation.has("slaMet")).isFalse();
        assertThat(conversation.has("customerUserId")).isFalse();
        assertThat(conversation.has("advisorUserId")).isFalse();
    }

    // ------------------------------------------------------------------ the whole call

    @Test
    @DisplayName("the life of a call: opened, taken, chatted, resolved — every step stored and measured")
    void lifeOfACall() throws Exception {
        JsonNode opened = call(open(amina, skill, "FRAUD", as(UserRole.SUPERVISOR)), 201);
        UUID id = UUID.fromString(opened.get("id").asText());
        assertThat(opened.get("status").asText()).isEqualTo("QUEUED");
        assertThat(opened.get("skill").asText()).isEqualTo(skill);
        assertThat(opened.get("intent").asText()).isEqualTo("FRAUD");
        assertNoInternalFields(opened);
        assertThat(fx.reload(id).getPriorityScore()).isEqualByComparingTo("65");

        JsonNode queues = call(get("/api/v1/queues").with(karim()), 200);
        assertThat(queues.get("queues")).anySatisfy(q -> {
            assertThat(q.get("skill").asText()).isEqualTo(skill);
            assertThat(q.get("waiting").asLong()).isEqualTo(1);
        });

        JsonNode taken = call(post("/api/v1/queues/{skill}/next", skill).with(karim()), 200);
        assertThat(taken.get("id").asText()).isEqualTo(id.toString());
        assertThat(taken.get("status").asText()).isEqualTo("ASSIGNED");
        assertNoInternalFields(taken);
        Conversation assigned = fx.reload(id);
        assertThat(assigned.getWaitSeconds()).isNotNull();
        assertThat(assigned.getSlaMet()).isTrue();

        JsonNode advisorMessage = call(message(id, "Bonjour Madame Haddad, je regarde votre carte.", karim()), 201);
        assertThat(advisorMessage.get("sender").asText()).isEqualTo("ADVISOR");
        assertThat(fx.reload(id).getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        JsonNode customerMessage = call(message(id, "Je n'ai pas fait ces paiements.", aminaLogin()), 201);
        assertThat(customerMessage.get("sender").asText()).isEqualTo("CUSTOMER");

        JsonNode transcript = call(get("/api/v1/conversations/{id}/messages", id).with(aminaLogin()), 200);
        assertThat(transcript.get("conversationId").asText()).isEqualTo(id.toString());
        assertThat(transcript.get("messages")).hasSize(2);
        assertThat(transcript.get("messages").get(0).get("sender").asText()).isEqualTo("ADVISOR");
        assertThat(transcript.get("messages").get(0).has("sources")).isFalse();
        assertThat(transcript.get("messages").get(0).has("toolCalls")).isFalse();

        JsonNode mine = call(get("/api/v1/conversations/mine").with(karim()), 200);
        assertThat(mine.get("conversations")).singleElement()
                .satisfies(c -> assertThat(c.get("id").asText()).isEqualTo(id.toString()));

        JsonNode resolved = call(post("/api/v1/conversations/{id}/resolve", id).with(karim()), 200);
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        Conversation stored = fx.reload(id);
        assertThat(stored.getStatus()).isEqualTo(ConversationStatus.RESOLVED);
        assertThat(stored.getEndedAt()).isNotNull();
        assertThat(stored.getHandleSeconds()).isNotNull();

        call(message(id, "encore une question", aminaLogin()), 409);
        assertThat(call(get("/api/v1/conversations/mine").with(karim()), 200).get("conversations")).isEmpty();
    }

    // ------------------------------------------------------------------ open

    @Nested
    @DisplayName("POST /api/v1/conversations")
    class Open {

        @Test
        @DisplayName("staff register a contact; a customer cannot (that needs S-1); anonymous is 401")
        void roles() throws Exception {
            call(open(amina, skill, null, as(UserRole.ADVISOR)), 201);
            call(open(amina, skill, null, as(UserRole.ADMIN)), 201);
            call(open(amina, skill, null, aminaLogin()), 403);
            call(open(amina, skill, null, request -> request), 401);
        }

        @Test
        @DisplayName("an unknown customer or skill is 404; a malformed skill code or missing customer is 400")
        void validation() throws Exception {
            ErrorEnvelope.assertConforms(call(open(UUID.randomUUID(), skill, null, as(UserRole.SUPERVISOR)), 404),
                    404, "RESOURCE_NOT_FOUND");
            call(open(amina, "NOPE" + ConversationFixtures.suffix(), null, as(UserRole.SUPERVISOR)), 404);
            ErrorEnvelope.assertConforms(call(open(amina, "bad skill!", null, as(UserRole.SUPERVISOR)), 400),
                    400, "VALIDATION_FAILED");
            call(post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"skill\": \"" + skill + "\"}").with(as(UserRole.SUPERVISOR)), 400);
            call(open(amina, skill, "NOT_AN_INTENT", as(UserRole.SUPERVISOR)), 400);
        }
    }

    // ------------------------------------------------------------------ queues

    @Nested
    @DisplayName("queues")
    class Queues {

        @Test
        @DisplayName("an empty queue answers 204 with no body")
        void empty() throws Exception {
            MvcResult r = mockMvc.perform(post("/api/v1/queues/{skill}/next", skill).with(karim())).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(204);
            assertThat(r.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("a queue the advisor holds no skill for is 403; a login with no advisor profile is 404")
        void refusals() throws Exception {
            String other = fx.skill(60);
            ErrorEnvelope.assertConforms(call(post("/api/v1/queues/{skill}/next", other).with(karim()), 403),
                    403, "ACCESS_DENIED");
            ErrorEnvelope.assertConforms(call(post("/api/v1/queues/{skill}/next", skill).with(as(UserRole.ADVISOR)), 404),
                    404, "ADVISOR_PROFILE_NOT_FOUND");
            call(post("/api/v1/queues/{skill}/next", "NOPE").with(karim()), 403);
            call(post("/api/v1/queues/{skill}/next", skill.toLowerCase()).with(karim()), 403);
            call(post("/api/v1/queues/{skill}/next", skill).with(as(UserRole.SUPERVISOR)), 403);
            call(post("/api/v1/queues/{skill}/next", skill).with(aminaLogin()), 403);
        }

        @Test
        @DisplayName("an advisor at max_concurrent is 409 ADVISOR_UNAVAILABLE, and the queue keeps its call")
        void capacity() throws Exception {
            fx.conversation(amina, skill, ConversationStatus.QUEUED, "0", Instant.now());
            UUID second = fx.conversation(amina, skill, ConversationStatus.QUEUED, "0", Instant.now());
            call(post("/api/v1/queues/{skill}/next", skill).with(karim()), 200);

            ErrorEnvelope.assertConforms(call(post("/api/v1/queues/{skill}/next", skill).with(karim()), 409),
                    409, "ADVISOR_UNAVAILABLE");
            assertThat(fx.reload(second).getStatus()).isEqualTo(ConversationStatus.QUEUED);
        }

        @Test
        @DisplayName("an advisor lists only their own skills' queues; a supervisor lists every queue; a customer none")
        void listing() throws Exception {
            String other = fx.skill(60);
            JsonNode advisorView = call(get("/api/v1/queues").with(karim()), 200);
            assertThat(advisorView.get("queues")).extracting(q -> q.get("skill").asText()).contains(skill)
                    .doesNotContain(other);
            JsonNode supervisorView = call(get("/api/v1/queues").with(as(UserRole.SUPERVISOR)), 200);
            assertThat(supervisorView.get("queues")).extracting(q -> q.get("skill").asText()).contains(skill, other);
            call(get("/api/v1/queues").with(aminaLogin()), 403);
        }
    }

    // ------------------------------------------------------------------ reading

    @Nested
    @DisplayName("who may read a conversation")
    class Reading {

        @Test
        @DisplayName("its customer, its advisor and a supervisor: 200; another advisor or customer: 404")
        void ownership() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.ACTIVE, "0", Instant.now(),
                    advisorId(karimUser), null);

            call(get("/api/v1/conversations/{id}", id).with(karim()), 200);
            call(get("/api/v1/conversations/{id}", id).with(aminaLogin()), 200);
            call(get("/api/v1/conversations/{id}", id).with(as(UserRole.SUPERVISOR)), 200);
            ErrorEnvelope.assertConforms(call(get("/api/v1/conversations/{id}", id).with(lina()), 404),
                    404, "RESOURCE_NOT_FOUND");
            call(get("/api/v1/conversations/{id}", id).with(as(UserRole.CUSTOMER)), 404);
            call(get("/api/v1/conversations/{id}/messages", id).with(lina()), 404);
            call(get("/api/v1/conversations/{id}", UUID.randomUUID()).with(as(UserRole.SUPERVISOR)), 404);
        }

        @Test
        @DisplayName("the transcript limit is 1 to 200; anything else is 400")
        void limit() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.QUEUED, "0", Instant.now());
            call(get("/api/v1/conversations/{id}/messages", id).param("limit", "200").with(aminaLogin()), 200);
            ErrorEnvelope.assertConforms(
                    call(get("/api/v1/conversations/{id}/messages", id).param("limit", "0").with(aminaLogin()), 400),
                    400, "VALIDATION_FAILED");
            call(get("/api/v1/conversations/{id}/messages", id).param("limit", "201").with(aminaLogin()), 400);
        }

        @Test
        @DisplayName("an advisor with no profile asking for their conversations is 404 ADVISOR_PROFILE_NOT_FOUND")
        void mineWithoutProfile() throws Exception {
            ErrorEnvelope.assertConforms(call(get("/api/v1/conversations/mine").with(as(UserRole.ADVISOR)), 404),
                    404, "ADVISOR_PROFILE_NOT_FOUND");
            call(get("/api/v1/conversations/mine").with(as(UserRole.SUPERVISOR)), 403);
        }
    }

    // ------------------------------------------------------------------ writing

    @Nested
    @DisplayName("messages")
    class Messages {

        @Test
        @DisplayName("a supervisor reads a chat but cannot write in it; another advisor cannot even see it")
        void writers() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.ACTIVE, "0", Instant.now(),
                    advisorId(karimUser), null);
            call(message(id, "x", as(UserRole.SUPERVISOR)), 403);
            call(message(id, "x", lina()), 404);
            call(message(id, "x", as(UserRole.CUSTOMER)), 404);
            call(message(id, "Bonjour", karim()), 201);
        }

        @Test
        @DisplayName("blank or over 2000 characters is 400; a sender in the body is ignored")
        void validation() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.ACTIVE, "0", Instant.now(),
                    advisorId(karimUser), null);
            ErrorEnvelope.assertConforms(call(message(id, "   ", karim()), 400), 400, "VALIDATION_FAILED");
            call(message(id, "x".repeat(2001), karim()), 400);
            call(message(id, "x".repeat(2000), karim()), 201);

            JsonNode posted = call(post("/api/v1/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\": \"hello\", \"sender\": \"SYSTEM\", \"aiGenerated\": true}").with(karim()), 201);
            assertThat(posted.get("sender").asText()).isEqualTo("ADVISOR");
        }

        @Test
        @DisplayName("an advisor cannot write in a conversation still in the queue: 404, it is nobody's yet")
        void queued() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.QUEUED, "0", Instant.now());
            call(message(id, "x", karim()), 404);
        }
    }

    // ------------------------------------------------------------------ closing

    @Nested
    @DisplayName("resolve and abandon")
    class Closing {

        @Test
        @DisplayName("an escalated conversation: the advisor gets 403, an admin 403, the supervisor resolves it")
        void escalated() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.ESCALATED, "0", Instant.now(),
                    advisorId(karimUser), null);
            UUID escalation = fx.pendingEscalation(id);
            UUID sarah = fx.user(UserRole.SUPERVISOR);

            ErrorEnvelope.assertConforms(call(post("/api/v1/conversations/{id}/resolve", id).with(karim()), 403),
                    403, "ACCESS_DENIED");
            call(post("/api/v1/conversations/{id}/resolve", id).with(as(UserRole.ADMIN)), 403);
            call(post("/api/v1/conversations/{id}/resolve", id).with(as(sarah, UserRole.SUPERVISOR)), 200);

            assertThat(fx.reload(id).getStatus()).isEqualTo(ConversationStatus.RESOLVED);
            assertThat(fx.reloadEscalation(escalation).getResolvedBy().getId()).isEqualTo(sarah);
        }

        @Test
        @DisplayName("resolving before the advisor engaged is 409 INVALID_STATE_TRANSITION; nothing changes")
        void tooEarly() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.ASSIGNED, "0", Instant.now(),
                    advisorId(karimUser), null);
            ErrorEnvelope.assertConforms(call(post("/api/v1/conversations/{id}/resolve", id).with(karim()), 409),
                    409, "INVALID_STATE_TRANSITION");
            assertThat(fx.reload(id).getStatus()).isEqualTo(ConversationStatus.ASSIGNED);
        }

        @Test
        @DisplayName("the customer who hangs up while waiting abandons the call; the wait is recorded")
        void customerAbandons() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.QUEUED, "0", Instant.now().minusSeconds(95));

            JsonNode r = call(post("/api/v1/conversations/{id}/abandon", id).with(aminaLogin()), 200);

            assertThat(r.get("status").asText()).isEqualTo("ABANDONED");
            Conversation stored = fx.reload(id);
            assertThat(stored.getWaitSeconds()).isBetween(95, 120);
            assertThat(stored.getHandleSeconds()).isNull();
            call(post("/api/v1/conversations/{id}/abandon", id).with(aminaLogin()), 409);
        }

        @Test
        @DisplayName("someone else's customer cannot abandon it: 404")
        void strangerAbandons() throws Exception {
            UUID id = fx.conversation(amina, skill, ConversationStatus.QUEUED, "0", Instant.now());
            call(post("/api/v1/conversations/{id}/abandon", id).with(as(UserRole.CUSTOMER)), 404);
            assertThat(fx.reload(id).getStatus()).isEqualTo(ConversationStatus.QUEUED);
        }
    }

    // ------------------------------------------------------------------ supervision

    @Test
    @DisplayName("live KPIs: supervisors and admins read them; advisors and customers do not")
    void kpi() throws Exception {
        JsonNode kpi = call(get("/api/v1/supervision/kpi").with(as(UserRole.SUPERVISOR)), 200);
        assertThat(kpi.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(kpi.get("queues").isArray()).isTrue();
        assertThat(kpi.has("waitingTotal")).isTrue();
        assertThat(kpi.has("slaRatio")).isTrue();
        call(get("/api/v1/supervision/kpi").with(as(UserRole.ADMIN)), 200);
        call(get("/api/v1/supervision/kpi").with(karim()), 403);
        call(get("/api/v1/supervision/kpi").with(aminaLogin()), 403);
        call(get("/api/v1/supervision/kpi").with(request -> request), 401);
    }

    private UUID advisorId(UUID userId) {
        return tx.execute(s -> em.createQuery("select a.id from Advisor a where a.user.id = :u", UUID.class)
                .setParameter("u", userId).getSingleResult());
    }
}
