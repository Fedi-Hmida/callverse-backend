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
                .isEqualTo("Je ne reconnais pas ce paiement.");
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
    }

    @Test
    void aiStaysSilentOnceTheHumanAdvisorHasWritten() throws Exception {
        String skill = fixtures.skill(60);
        UUID customerUserId = fixtures.user(UserRole.CUSTOMER);
        UUID customerId = fixtures.customer(customerUserId, ChurnRisk.LOW, CustomerSegment.MASS);
        UUID advisorId = fixtures.advisor(fixtures.user(UserRole.ADVISOR), 1, skill);
        Instant queuedAt = Instant.now().minusSeconds(60);
        UUID conversationId = fixtures.conversation(
                customerId, skill, ConversationStatus.ACTIVE, "0", queuedAt, advisorId, null);
        fixtures.message(conversationId, MessageSender.ADVISOR, "Bonjour, Karim ici.", queuedAt.plusSeconds(11));

        mockMvc.perform(customerMessage(conversationId, customerUserId, "customer-takeover-test"))
                .andExpect(status().isCreated());

        // Long enough for an AI turn to land if one had been scheduled (the first test sees it in ms).
        assertThat(awaitAiTurn(conversationId, queuedAt, java.time.Duration.ofMillis(1500))).isEmpty();
        assertThat(LAST_ADVISOR_REQUEST.get()).as("the AI service was never called").isNull();
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
        assertThat(escalation).containsExactly("AI", "Le client demande un superviseur", "PENDING");
        assertThat(awaitAiTurn(conversationId, queuedAt)).as("the reply is posted before escalating").hasSize(1);
    }

    private MockHttpServletRequestBuilder customerMessage(UUID conversationId, UUID customerUserId, String login)
            throws Exception {
        return customerMessage(conversationId, customerUserId, login, "Je ne reconnais pas ce paiement.");
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
                // A customer asking for a supervisor gets the Advisor's ESCALATE action.
                String action = request.path("message").asText().contains("superviseur")
                        ? "{\"type\":\"ESCALATE\",\"payload\":{\"reason\":\"Le client demande un superviseur\"}}"
                        : "{\"type\":\"NONE\",\"payload\":{}}";
                byte[] response = """
                        {
                          "reply": "Je vérifie ce paiement.",
                          "intent": "FRAUD",
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
