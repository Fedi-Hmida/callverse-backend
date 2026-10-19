package com.callverse.conversation;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.tokenForExistingAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.callverse.core.domain.entities.Message;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.infrastructure.persistence.repositories.MessageRepository;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class AdvisorTurnIntegrationTest extends AbstractPersistenceTest {

    private static final AtomicReference<JsonNode> LAST_ADVISOR_REQUEST = new AtomicReference<>();
    private static final String API_KEY = "fake-ai-service-key-for-integration-test";
    private static final HttpServer AI_SERVER = startAiServer();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired MessageRepository messageRepository;
    @Autowired com.callverse.core.application.interfaces.ConversationMessages conversationMessages;

    private ConversationFixtures fixtures;

    @DynamicPropertySource
    static void advisorProperties(DynamicPropertyRegistry registry) {
        registry.add("ai.service.base-url", () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort());
        registry.add("ai.service.api-key", () -> API_KEY);
        registry.add("ai.advisor.mode", () -> "AUTONOMOUS");
    }

    @AfterAll
    static void stopAiServer() {
        AI_SERVER.stop(0);
    }

    @BeforeEach
    void setUp() {
        LAST_ADVISOR_REQUEST.set(null);
        fixtures = new ConversationFixtures(em, tx);
    }

    @Test
    void customerMessageGetsAnAiTurnPersistedWithPrivateTrace() throws Exception {
        String skill = fixtures.skill(60);
        UUID customerUserId = fixtures.user(UserRole.CUSTOMER);
        UUID customerId = fixtures.customer(customerUserId, ChurnRisk.HIGH, CustomerSegment.AFFLUENT);
        UUID advisorUserId = fixtures.user(UserRole.ADVISOR);
        UUID advisorId = fixtures.advisor(advisorUserId, 1, skill);
        Instant queuedAt = Instant.now().minusSeconds(60);
        UUID conversationId = fixtures.conversation(
                customerId, skill, ConversationStatus.ASSIGNED, "0", queuedAt, advisorId, null);
        fixtures.aiAdvisorMessage(conversationId, "Bonjour.", queuedAt.plusSeconds(11));

        MvcResult result = mockMvc.perform(customerMessage(conversationId, customerUserId, "customer-advisor-test"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode customerMessage = objectMapper.readTree(result.getResponse().getContentAsString());

        List<Message> aiTurns = awaitAiTurn(conversationId, queuedAt.plusSeconds(11));

        assertThat(aiTurns).hasSize(1);
        Message aiTurn = aiTurns.getFirst();
        assertThat(aiTurn.getSender()).isEqualTo(MessageSender.ADVISOR);
        assertThat(aiTurn.isAiGenerated()).isTrue();
        assertThat(aiTurn.getContent()).isEqualTo("Je vérifie ce paiement.");
        assertThat(aiTurn.getSources().size()).isEqualTo(1);
        assertThat(aiTurn.getToolCalls().size()).isEqualTo(1);
        assertThat(fixtures.reload(conversationId).getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(LAST_ADVISOR_REQUEST.get().path("conversation_id").asText()).isEqualTo(conversationId.toString());
        assertThat(LAST_ADVISOR_REQUEST.get().path("message").asText())
                .isEqualTo("Pouvez-vous vérifier ce paiement ?");
        assertThat(LAST_ADVISOR_REQUEST.get().path("history").size()).isEqualTo(1);
        assertThat(LAST_ADVISOR_REQUEST.get().path("history").get(0).path("role").asText()).isEqualTo("advisor");
        assertThat(LAST_ADVISOR_REQUEST.get().path("context").path("tenure_months").asInt()).isZero();
        assertThat(LAST_ADVISOR_REQUEST.get().path("context").path("segment").asText()).isEqualTo("AFFLUENT");
        assertThat(LAST_ADVISOR_REQUEST.get().path("context").has("churn_risk")).isFalse();
        assertThat(LAST_ADVISOR_REQUEST.get().path("context").has("contract_type")).isFalse();
        assertThat(LAST_ADVISOR_REQUEST.get().path("context").has("open_tickets")).isFalse();

        JsonNode publicMessage = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(publicMessage.get("id").asLong()).isEqualTo(customerMessage.get("id").asLong());
        assertThat(publicMessage.has("sources")).isFalse();
        assertThat(publicMessage.has("toolCalls")).isFalse();
        assertThat(publicMessage.get("aiGenerated").asBoolean()).as("the customer's own message").isFalse();

        // Transparency: the transcript tells the AI's replies apart from a person's.
        JsonNode transcript = objectMapper.readTree(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/conversations/{id}/messages", conversationId)
                                .with(bearer(tokenForExistingAccount(
                                        customerUserId, "customer-advisor-test@accounts.test", UserRole.CUSTOMER))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("messages");
        JsonNode last = transcript.get(transcript.size() - 1);
        assertThat(last.get("content").asText()).isEqualTo("Je vérifie ce paiement.");
        assertThat(last.get("aiGenerated").asBoolean()).isTrue();
        assertThat(transcript.get(transcript.size() - 2).get("aiGenerated").asBoolean()).isFalse();

        var staff = bearer(tokenForExistingAccount(advisorUserId, "assigned@accounts.test", UserRole.ADVISOR));
        JsonNode explanation = objectMapper.readTree(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/conversations/{id}/messages/{messageId}/explanation", conversationId, aiTurn.getId()).with(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(explanation.path("intent").asText()).isEqualTo("CARD");
        assertThat(explanation.path("confidence").asDouble()).isEqualTo(.9);
        assertThat(explanation.path("sources").get(0).path("articleId").asText()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(explanation.path("checks").get(0).path("tool").asText()).isEqualTo("get_transactions");
        assertThat(explanation.path("checks").get(0).path("ok").asBoolean()).isTrue();
        assertThat(explanation.toString()).doesNotContain("result_summary", "args");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/conversations/{id}/messages/{messageId}/explanation", conversationId, customerMessage.path("id").asLong()).with(staff))
                .andExpect(status().isNotFound());
        long oldId = transcript.get(0).path("id").asLong();
        JsonNode legacy = objectMapper.readTree(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/conversations/{id}/messages/{messageId}/explanation", conversationId, oldId).with(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(legacy.path("decisionRecorded").asBoolean()).isFalse();
        assertThat(legacy.path("confidence").isNull()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ConversationStatus.class, names = {"ACTIVE", "ESCALATED"})
    void aiStopsAfterTheHumanAdvisorHasWritten(ConversationStatus state) throws Exception {
        String skill = fixtures.skill(60);
        UUID customerUserId = fixtures.user(UserRole.CUSTOMER);
        UUID customerId = fixtures.customer(customerUserId, ChurnRisk.LOW, CustomerSegment.MASS);
        UUID advisorId = fixtures.advisor(fixtures.user(UserRole.ADVISOR), 1, skill);
        Instant queuedAt = Instant.now().minusSeconds(60);
        UUID conversationId = fixtures.conversation(
                customerId, skill, state, "0", queuedAt, advisorId, null);
        fixtures.message(conversationId, MessageSender.ADVISOR, "Bonjour, Karim ici.", queuedAt.plusSeconds(11));

        mockMvc.perform(customerMessage(conversationId, customerUserId, "customer-takeover-test"))
                .andExpect(status().isCreated());

        assertThat(awaitAiTurn(conversationId, queuedAt)).isEmpty();
        assertThat(LAST_ADVISOR_REQUEST.get()).isNull();
    }

    @Test
    void pendingAiWriteIsRefusedAfterHumanTakeoverEvenWhenTheCustomerWritesAgain() {
        String skill = fixtures.skill(60);
        UUID customerId = fixtures.customer();
        UUID advisorId = fixtures.advisor(fixtures.user(UserRole.ADVISOR), 1, skill);
        Instant now = Instant.now();
        UUID conversationId = fixtures.conversation(
                customerId, skill, ConversationStatus.ESCALATED, "0", now.minusSeconds(60), advisorId, null);
        fixtures.message(conversationId, MessageSender.ADVISOR, "Je prends le relais.", now.minusSeconds(2));
        fixtures.message(conversationId, MessageSender.CUSTOMER, "Merci, voici les détails.", now.minusSeconds(1));
        long latestId = conversationMessages.recent(conversationId, 1).getFirst().id();

        assertThat(conversationMessages.postAgentTurn(
                conversationId, latestId, "Réponse IA tardive", List.of(), List.of(), now)).isEmpty();
        assertThat(conversationMessages.recent(conversationId, 10)).hasSize(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ConversationStatus.class, names = {"QUEUED", "ESCALATED"})
    void aiAnswersOpenContactsWithoutChangingTheirRoutingState(ConversationStatus state) throws Exception {
        String skill = fixtures.skill(60);
        UUID customerUserId = fixtures.user(UserRole.CUSTOMER);
        UUID customerId = fixtures.customer(customerUserId, ChurnRisk.LOW, CustomerSegment.MASS);
        Instant queuedAt = Instant.now().minusSeconds(60);
        UUID conversationId = fixtures.conversation(customerId, skill, state, "0", queuedAt, null, null);
        mockMvc.perform(customerMessage(conversationId, customerUserId, "open-ai-contact"))
                .andExpect(status().isCreated());
        assertThat(awaitAiTurn(conversationId, queuedAt)).singleElement()
                .satisfies(message -> assertThat(message.isAiGenerated()).isTrue());
        assertThat(fixtures.reload(conversationId).getStatus()).isEqualTo(state);
        assertThat(fixtures.reload(conversationId).getAdvisor()).isNull();
    }

    @Test
    void queuedFraudContactIsEscalatedWithoutNeedingAnAdvisor() throws Exception {
        String skill = fixtures.skill(60);
        UUID customerUserId = fixtures.user(UserRole.CUSTOMER);
        UUID customerId = fixtures.customer(customerUserId, ChurnRisk.LOW, CustomerSegment.MASS);
        Instant queuedAt = Instant.now().minusSeconds(60);
        UUID conversationId = fixtures.conversation(customerId, skill, ConversationStatus.QUEUED, "0", queuedAt, null, null);
        mockMvc.perform(customerMessage(conversationId, customerUserId, "queued-fraud", "Je ne reconnais pas ce paiement."))
                .andExpect(status().isCreated());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && fixtures.reload(conversationId).getStatus() != ConversationStatus.ESCALATED) Thread.sleep(25);
        assertThat(fixtures.reload(conversationId).getStatus()).isEqualTo(ConversationStatus.ESCALATED);
        assertThat(awaitAiTurn(conversationId, queuedAt)).hasSize(1);
        Object count = tx.execute(status -> em.createNativeQuery("select count(*) from escalation where conversation_id=:id and status='PENDING'")
                .setParameter("id", conversationId).getSingleResult());
        assertThat(((Number) count).intValue()).isEqualTo(1);
    }

    @Test
    void anEscalateActionMovesTheConversationToEscalatedAsRaisedByTheAi() throws Exception {
        String skill = fixtures.skill(60);
        UUID customerUserId = fixtures.user(UserRole.CUSTOMER);
        UUID customerId = fixtures.customer(customerUserId, ChurnRisk.LOW, CustomerSegment.MASS);
        UUID advisorId = fixtures.advisor(fixtures.user(UserRole.ADVISOR), 1, skill);
        Instant queuedAt = Instant.now().minusSeconds(60);
        // ASSIGNED: the AI reply moves it to ACTIVE, the only state the escalation rules accept.
        UUID conversationId = fixtures.conversation(
                customerId, skill, ConversationStatus.ASSIGNED, "0", queuedAt, advisorId, null);

        mockMvc.perform(customerMessage(
                        conversationId, customerUserId, "customer-escalate-test", "Je veux parler à un superviseur."))
                .andExpect(status().isCreated());

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
                && fixtures.reload(conversationId).getStatus() != ConversationStatus.ESCALATED) {
            Thread.sleep(25);
        }
        assertThat(fixtures.reload(conversationId).getStatus()).isEqualTo(ConversationStatus.ESCALATED);
        Object[] escalation = tx.execute(status -> (Object[]) em.createNativeQuery(
                        "select raised_by, reason, status from escalation where conversation_id = :id")
                .setParameter("id", conversationId)
                .getSingleResult());
        assertThat(escalation).containsExactly("AI", "Reprise humaine demandée par le client", "PENDING");
        assertThat(LAST_ADVISOR_REQUEST.get()).as("handover must not depend on a provider").isNull();
        assertThat(awaitAiTurn(conversationId, queuedAt)).as("the reply is posted before escalating").hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Je ne reconnais pas ce paiement.", "quota-test"})
    void fraudAndUnavailableProviderPersistAHumanHandover(String content) throws Exception {
        String skill = fixtures.skill(60);
        UUID customerUserId = fixtures.user(UserRole.CUSTOMER);
        UUID customerId = fixtures.customer(customerUserId, ChurnRisk.LOW, CustomerSegment.MASS);
        UUID advisorId = fixtures.advisor(fixtures.user(UserRole.ADVISOR), 1, skill);
        Instant queuedAt = Instant.now().minusSeconds(60);
        UUID conversationId = fixtures.conversation(
                customerId, skill, ConversationStatus.ASSIGNED, "0", queuedAt, advisorId, null);
        mockMvc.perform(customerMessage(conversationId, customerUserId, "safety-test", content))
                .andExpect(status().isCreated());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
                && fixtures.reload(conversationId).getStatus() != ConversationStatus.ESCALATED) {
            Thread.sleep(25);
        }
        assertThat(fixtures.reload(conversationId).getStatus()).isEqualTo(ConversationStatus.ESCALATED);
        List<Message> turns = awaitAiTurn(conversationId, queuedAt);
        assertThat(turns).hasSize(1);
        if (content.equals("quota-test")) {
            assertThat(turns.getFirst().getContent()).contains("temporairement indisponible");
            assertThat(LAST_ADVISOR_REQUEST.get()).isNotNull();
        } else {
            assertThat(turns.getFirst().getContent()).contains("Aucun blocage");
            assertThat(LAST_ADVISOR_REQUEST.get()).isNull();
        }
        Object[] record = tx.execute(status -> (Object[]) em.createNativeQuery(
                        "select raised_by,status from escalation where conversation_id=:id")
                .setParameter("id", conversationId).getSingleResult());
        assertThat(record).containsExactly("AI", "PENDING");
    }

    @Test
    void explicitHumanRequestFromQueuePersistsExplanationAndKeepsAiAvailableUntilHumanReply() throws Exception {
        String skill = fixtures.skill(60);
        UUID user = fixtures.user(UserRole.CUSTOMER);
        UUID customer = fixtures.customer(user, ChurnRisk.LOW, CustomerSegment.MASS);
        Instant before = Instant.now().minusSeconds(60);
        UUID id = fixtures.conversation(customer, skill, ConversationStatus.QUEUED, "0", before, null, null);
        mockMvc.perform(customerMessage(id, user, "request-human", "Je veux un conseiller humain svp"))
                .andExpect(status().isCreated());
        Message first = awaitAiTurn(id, before).getFirst();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (fixtures.reload(id).getStatus() != ConversationStatus.ESCALATED && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(fixtures.reload(id).getStatus()).isEqualTo(ConversationStatus.ESCALATED);
        assertThat(first.getContent()).contains("Je reste disponible");
        assertThat(LAST_ADVISOR_REQUEST.get()).isNull();

        String path = "/api/v1/conversations/" + id + "/messages/" + first.getId() + "/explanation";
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
        mockMvc.perform(get.with(bearer(tokenForExistingAccount(user, "owner@accounts.test", UserRole.CUSTOMER))))
                .andExpect(status().isForbidden());
        UUID supervisor = fixtures.user(UserRole.SUPERVISOR);
        var staff = bearer(tokenForExistingAccount(supervisor, "supervisor@accounts.test", UserRole.SUPERVISOR));
        JsonNode explanation = objectMapper.readTree(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(explanation.path("decisionRecorded").asBoolean()).isTrue();
        assertThat(explanation.path("reason").asText()).isEqualTo("Reprise humaine demandée par le client");
        assertThat(explanation.path("action").asText()).isEqualTo("ESCALATE");
        assertThat(explanation.path("checks").size()).isZero();
        UUID outsider = fixtures.user(UserRole.ADVISOR);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .with(bearer(tokenForExistingAccount(outsider, "outsider@accounts.test", UserRole.ADVISOR))))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/conversations/" + UUID.randomUUID() + "/messages/" + first.getId() + "/explanation").with(staff))
                .andExpect(status().isNotFound());

        mockMvc.perform(customerMessage(id, user, "request-human", "Puis-je préciser ma demande ?"))
                .andExpect(status().isCreated());
        assertThat(awaitAiTurn(id, first.getSentAt())).hasSize(1);
        assertThat(fixtures.reload(id).getStatus()).isEqualTo(ConversationStatus.ESCALATED);
    }

    private MockHttpServletRequestBuilder customerMessage(UUID conversationId, UUID customerUserId, String login)
            throws Exception {
        return customerMessage(conversationId, customerUserId, login, "Pouvez-vous vérifier ce paiement ?");
    }

    private MockHttpServletRequestBuilder customerMessage(
            UUID conversationId, UUID customerUserId, String login, String content) throws Exception {
        RequestPostProcessor authentication = bearer(tokenForExistingAccount(
                customerUserId, login + "@accounts.test", UserRole.CUSTOMER));
        return post("/api/v1/conversations/{id}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of("content", content)))
                .with(authentication);
    }

    /** AI turns written after {@code after}, so a seeded AI greeting does not count as the answer. */
    private List<Message> awaitAiTurn(UUID conversationId, Instant after) throws InterruptedException {
        return awaitAiTurn(conversationId, after, java.time.Duration.ofSeconds(10));
    }

    private List<Message> awaitAiTurn(UUID conversationId, Instant after, java.time.Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Message> found = List.of();
        while (System.nanoTime() < deadline) {
            found = tx.execute(status -> messageRepository.findByConversationIdAndAiGeneratedTrueOrderBySentAtAsc(
                            conversationId)
                    .stream()
                    .filter(message -> message.getSentAt().isAfter(after))
                    .toList());
            if (!found.isEmpty()) {
                return found;
            }
            Thread.sleep(25);
        }
        return found;
    }

    private static HttpServer startAiServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/ai/advisor/respond", exchange -> {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode request = mapper.readTree(requestBody);
                LAST_ADVISOR_REQUEST.set(request);
                if (request.path("message").asText().equals("quota-test")) {
                    exchange.sendResponseHeaders(429, -1);
                    exchange.close();
                    return;
                }
                // A customer asking for a supervisor gets the Advisor's ESCALATE action.
                String action = request.path("message").asText().contains("superviseur")
                        ? "{\"type\":\"ESCALATE\",\"payload\":{\"reason\":\"Le client demande un superviseur\"}}"
                        : "{\"type\":\"NONE\",\"payload\":{}}";
                byte[] response = """
                        {
                          "reply": "Je vérifie ce paiement.",
                          "intent": "CARD",
                          "confidence": 0.9,
                          "tool_calls": [
                            {"tool":"get_transactions","args":{"limit":5},"result_summary":"5 transactions","ok":true}
                          ],
                          "sources": [
                            {"kb_article_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","score":0.91}
                          ],
                          "action": %s,
                          "latency_ms":17
                        }
                        """.formatted(action).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
