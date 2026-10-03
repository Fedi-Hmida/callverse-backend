package com.callverse.realtime;

import static com.callverse.auth.AuthenticatedRequests.expiredToken;
import static com.callverse.auth.AuthenticatedRequests.tokenForExistingAccount;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.CardNetwork;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.CardType;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.util.List;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Live supervision over a real STOMP connection, against the deny-by-default {@code prod} chain.
 *
 * <p>Every security rule has a refusal test and a success test: connecting needs a valid token,
 * supervision topics need a SUPERVISOR or ADMIN, unknown destinations are refused, and no client may
 * publish. The alerts are triggered by real HTTP calls from an advisor, and must arrive only for real
 * transitions — never for an idempotent repeat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class StompSupervisionTest extends AbstractPersistenceTest {

    private static final String ALERTS = "/topic/supervision/alerts";

    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private final WebSocketStompClient client = stompClient();
    private final HttpClient http = HttpClient.newHttpClient();

    private static WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // Text for ERROR frames, JSON for alerts.
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
        return client;
    }

    private final List<StompSession> sessions = new java.util.ArrayList<>();

    @AfterEach
    void stop() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
        client.stop();
    }

    // ------------------------------------------------------------------ STOMP plumbing

    /** Records how the server treated the session: connected, then possibly refused or dropped. */
    static final class Probe extends StompSessionHandlerAdapter {
        final AtomicBoolean connected = new AtomicBoolean();
        final AtomicBoolean refused = new AtomicBoolean();
        volatile String errorMessage;

        @Override
        public void afterConnected(StompSession session, StompHeaders headers) {
            connected.set(true);
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errorMessage = headers.getFirst("message"); // a session-level frame is an ERROR frame
            refused.set(true);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            refused.set(true);
        }

        @Override
        public void handleException(
                StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable e) {
            refused.set(true);
        }
    }

    private Probe connect(String token) {
        Probe probe = new Probe();
        StompHeaders connect = new StompHeaders();
        if (token != null) {
            connect.add("Authorization", "Bearer " + token);
        }
        try {
            client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect, probe)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception refusedOrDropped) {
            probe.refused.set(true);
        }
        waitFor(() -> probe.connected.get() || probe.refused.get());
        return probe;
    }

    private StompSession session(String token) throws Exception {
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                        connect, new Probe())
                .get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        pause(300); // the SUBSCRIBE frame is asynchronous; give the broker time to register it
        return received;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            pause(50);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String token(UserRole role) {
        return validToken(UUID.randomUUID(), role.name().toLowerCase() + "@callverse.local", role);
    }

    /**
     * Subscribes on a fresh session and reports whether the server refused the subscription — with
     * its "Access denied" ERROR frame, so an unrelated breakage cannot pass for a refusal.
     */
    private boolean subscriptionRefused(UserRole role, String destination) throws Exception {
        Probe probe = new Probe();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token(role));
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                        connect, probe)
                .get(5, TimeUnit.SECONDS);
        sessions.add(session);
        assertThat(probe.connected.get()).as("%s must be able to connect at all", role).isTrue();
        session.subscribe(destination, new StompSessionHandlerAdapter() {});
        long deadline = System.currentTimeMillis() + 1500;
        while (!probe.refused.get() && System.currentTimeMillis() < deadline) {
            pause(50);
        }
        if (probe.refused.get()) {
            assertThat(probe.errorMessage).as("refused by the interceptor, not by a broken socket").isEqualTo("Access denied");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private int post(String path, String token, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** @param advisorToken a token for the advisor the conversation is assigned to */
    private record Seed(UUID customerId, UUID conversationId, UUID cardId, String advisorToken) {}

    /** Committed rows: the test talks to a real server in another thread, so nothing may roll back. */
    private Seed seed() {
        return tx.execute(status -> {
            Customer customer = new Customer();
            customer.setExternalRef("RT-" + UUID.randomUUID().toString().substring(0, 8));
            customer.setFirstName("Amina");
            customer.setLastName("Haddad");
            customer.setRegion("Marseille");
            em.persist(customer);

            // Only the conversation's own advisor may escalate it, so the seed makes a real one.
            AppUser karim = new AppUser();
            karim.setEmail("karim-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local");
            karim.setPasswordHash("$2a$10$abcdefghijklmnopqrstuuJ4H1y9oD6kBz1V5Q2yQ1w5bLb6xXyZ2");
            karim.setFirstName("Karim");
            karim.setLastName("Advisor");
            karim.setRole(UserRole.ADVISOR);
            em.persist(karim);
            Advisor advisor = new Advisor();
            advisor.setUser(karim);
            advisor.setDisplayName("Karim");
            em.persist(advisor);

            Conversation conversation = new Conversation();
            conversation.setCustomer(customer);
            conversation.setStatus(ConversationStatus.ACTIVE);
            conversation.setAdvisor(advisor);
            em.persist(conversation);

            Account account = new Account();
            account.setCustomer(customer);
            account.setProduct(em.createQuery(
                            "select p from BankingProduct p where p.code = 'CUR_ESSENTIAL'", BankingProduct.class)
                    .getSingleResult());
            account.setIban("FR76" + UUID.randomUUID().toString().replace("-", "").substring(0, 23).toUpperCase());
            account.setStatus(AccountStatus.ACTIVE);
            account.setOpenedAt(LocalDate.of(2020, 1, 1));
            em.persist(account);

            Card card = new Card();
            card.setAccount(account);
            card.setPanLast4("4242");
            card.setNetwork(CardNetwork.VISA);
            card.setType(CardType.DEBIT);
            card.setStatus(CardStatus.ACTIVE);
            card.setExpiresOn(LocalDate.of(2029, 12, 31));
            card.setDailyLimit(new BigDecimal("1000.00"));
            em.persist(card);
            return new Seed(customer.getId(), conversation.getId(), card.getId(),
                    tokenForExistingAccount(karim.getId(), karim.getEmail(), UserRole.ADVISOR));
        });
    }

    // ------------------------------------------------------------------ connecting

    @Test
    @DisplayName("a valid token connects")
    void validTokenConnects() {
        Probe probe = connect(token(UserRole.SUPERVISOR));
        assertThat(probe.connected.get()).isTrue();
        assertThat(probe.refused.get()).isFalse();
    }

    @Test
    @DisplayName("no token, a garbage token or an expired token cannot connect")
    void badTokensCannotConnect() {
        assertThat(connect(null).connected.get()).as("no token").isFalse();
        assertThat(connect("not.a.jwt").connected.get()).as("garbage token").isFalse();
        assertThat(connect(expiredToken(UUID.randomUUID(), "x@callverse.local", UserRole.SUPERVISOR))
                        .connected.get())
                .as("expired token")
                .isFalse();
    }

    // ------------------------------------------------------------------ subscribing

    @Test
    @DisplayName("supervision alerts: a SUPERVISOR and an ADMIN may listen, an ADVISOR and a CUSTOMER may not")
    void supervisionIsForSupervisors() throws Exception {
        assertThat(subscriptionRefused(UserRole.SUPERVISOR, ALERTS)).isFalse();
        assertThat(subscriptionRefused(UserRole.ADMIN, ALERTS)).isFalse();
        assertThat(subscriptionRefused(UserRole.ADVISOR, ALERTS)).isTrue();
        assertThat(subscriptionRefused(UserRole.CUSTOMER, ALERTS)).isTrue();
    }

    @Test
    @DisplayName("queues are for staff: a SUPERVISOR may listen to any skill queue, a CUSTOMER to none, and an "
            + "ADVISOR only to a skill they hold (proven with real advisors in StompConversationTest)")
    void queuesAreForStaff() throws Exception {
        assertThat(subscriptionRefused(UserRole.SUPERVISOR, "/topic/queue/CARDS")).isFalse();
        assertThat(subscriptionRefused(UserRole.CUSTOMER, "/topic/queue/CARDS")).isTrue();
        assertThat(subscriptionRefused(UserRole.ADVISOR, "/topic/queue/CARDS"))
                .as("a token with no advisor profile holds no skill").isTrue();
    }

    @Test
    @DisplayName("a destination outside the frozen topics is refused, whoever asks")
    void unknownDestinationIsRefused() throws Exception {
        assertThat(subscriptionRefused(UserRole.ADMIN, "/topic/anything-else")).isTrue();
        assertThat(subscriptionRefused(UserRole.ADMIN, "/user/queue/private")).isTrue();
    }

    // ------------------------------------------------------------------ publishing

    @Test
    @DisplayName("a client cannot publish: a forged alert is refused and never reaches a supervisor")
    void clientsCannotPublish() throws Exception {
        StompSession supervisor = session(token(UserRole.SUPERVISOR));
        BlockingQueue<JsonNode> received = subscribe(supervisor, ALERTS);

        Probe forger = new Probe();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token(UserRole.ADMIN));
        StompSession admin = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                        connect, forger)
                .get(5, TimeUnit.SECONDS);
        admin.send(ALERTS, "{\"type\":\"ESCALATION_RAISED\",\"forged\":true}");

        waitFor(() -> forger.refused.get());
        assertThat(forger.refused.get() || !admin.isConnected()).as("the SEND is refused").isTrue();
        assertThat(received.poll(1500, TimeUnit.MILLISECONDS)).as("nothing forged reaches a supervisor").isNull();
    }

    // ------------------------------------------------------------------ live alerts

    @Test
    @DisplayName("an advisor escalates: the supervisor sees ESCALATION_RAISED live, once")
    void escalationReachesTheSupervisorOnce() throws Exception {
        Seed seed = seed();
        BlockingQueue<JsonNode> received = subscribe(session(token(UserRole.SUPERVISOR)), ALERTS);

        String advisor = seed.advisorToken();
        String body = "{\"reason\": \"Fraude suspectee, besoin d'un superviseur\"}";
        assertThat(post("/api/v1/conversations/" + seed.conversationId() + "/escalations", advisor, body)).isEqualTo(201);

        JsonNode alert = received.poll(5, TimeUnit.SECONDS);
        assertThat(alert).as("an alert within five seconds").isNotNull();
        assertThat(alert.get("type").asText()).isEqualTo("ESCALATION_RAISED");
        assertThat(alert.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(alert.get("conversationId").asText()).isEqualTo(seed.conversationId().toString());
        assertThat(alert.get("customerId").asText()).isEqualTo(seed.customerId().toString());

        assertThat(post("/api/v1/conversations/" + seed.conversationId() + "/escalations", advisor, body)).isEqualTo(200);
        assertThat(received.poll(1500, TimeUnit.MILLISECONDS)).as("a repeat is not a new escalation").isNull();
    }

    @Test
    @DisplayName("a card blocked for suspected fraud raises CARD_BLOCKED_FRAUD; other reasons and repeats raise nothing")
    void fraudBlockReachesTheSupervisor() throws Exception {
        Seed fraud = seed();
        Seed lost = seed();
        BlockingQueue<JsonNode> received = subscribe(session(token(UserRole.SUPERVISOR)), ALERTS);
        String advisor = token(UserRole.ADVISOR);

        assertThat(post("/api/v1/cards/" + lost.cardId() + "/block", advisor, "{\"reason\": \"LOST\"}")).isEqualTo(200);
        assertThat(received.poll(1500, TimeUnit.MILLISECONDS)).as("a lost card is not a supervision alert").isNull();

        assertThat(post("/api/v1/cards/" + fraud.cardId() + "/block", advisor, "{\"reason\": \"FRAUD_SUSPECTED\"}"))
                .isEqualTo(200);
        JsonNode alert = received.poll(5, TimeUnit.SECONDS);
        assertThat(alert).as("an alert within five seconds").isNotNull();
        assertThat(alert.get("type").asText()).isEqualTo("CARD_BLOCKED_FRAUD");
        assertThat(alert.get("cardId").asText()).isEqualTo(fraud.cardId().toString());
        assertThat(alert.get("cardLast4").asText()).isEqualTo("4242");
        assertThat(alert.get("customerId").asText()).isEqualTo(fraud.customerId().toString());
        assertThat(alert.toString()).as("never an IBAN in an alert").doesNotContain("FR76");

        assertThat(post("/api/v1/cards/" + fraud.cardId() + "/block", advisor, "{\"reason\": \"FRAUD_SUSPECTED\"}"))
                .isEqualTo(200);
        assertThat(received.poll(1500, TimeUnit.MILLISECONDS)).as("a repeat block is not a new event").isNull();
    }

}
