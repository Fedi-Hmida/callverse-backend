package com.callverse.realtime;

import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.conversation.ConversationFixtures;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.callverse.realtime.LiveClient.Listener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The conversation core, live: messages, queue movements and KPIs reach the right screens over a
 * real STOMP connection, against the {@code prod} chain — and only the right screens. Every
 * ownership rule at subscribe time has its refusal next to its success.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class StompConversationTest extends AbstractPersistenceTest {

    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private LiveClient live;
    private ConversationFixtures fx;

    private String skill;
    private UUID karimUser;
    private UUID aminaUser;
    private UUID amina;
    private UUID karimAdvisor;

    @BeforeEach
    void setUp() {
        live = new LiveClient(port);
        fx = new ConversationFixtures(em, tx);
        skill = fx.skill(60);
        karimUser = fx.user(UserRole.ADVISOR);
        karimAdvisor = fx.advisor(karimUser, 2, skill);
        aminaUser = fx.user(UserRole.CUSTOMER);
        amina = fx.customer(aminaUser, ChurnRisk.MEDIUM, CustomerSegment.AFFLUENT);
    }

    @AfterEach
    void tearDown() {
        live.close();
    }

    private static String token(UUID user, UserRole role) {
        return validToken(user, role.name().toLowerCase() + "@test.local", role);
    }

    private static String token(UserRole role) {
        return token(UUID.randomUUID(), role);
    }

    private String karim() {
        return token(karimUser, UserRole.ADVISOR);
    }

    private String aminaLogin() {
        return token(aminaUser, UserRole.CUSTOMER);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        return keys;
    }

    // ------------------------------------------------------------------ conversation topic

    @Test
    @DisplayName("Karim and Amina each see the other's message live; the advisor's first message shows ACTIVE")
    void messagesBothWays() throws Exception {
        UUID id = fx.conversation(amina, skill, ConversationStatus.ASSIGNED, "0", Instant.now(), karimAdvisor, null);
        String topic = "/topic/conversation/" + id;
        Listener karimScreen = live.listen(karim(), topic);
        Listener aminaScreen = live.listen(aminaLogin(), topic);

        assertThat(post("/api/v1/conversations/" + id + "/messages", karim(),
                "{\"content\": \"Bonjour Madame Haddad\"}").statusCode()).isEqualTo(201);

        JsonNode toAmina = aminaScreen.next("MESSAGE_POSTED");
        assertThat(toAmina).as("Amina sees Karim's message within five seconds").isNotNull();
        assertThat(toAmina.get("sender").asText()).isEqualTo("ADVISOR");
        assertThat(toAmina.get("content").asText()).isEqualTo("Bonjour Madame Haddad");
        assertThat(toAmina.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(keys(toAmina)).containsExactly(
                "content", "conversationId", "messageId", "occurredAt", "schemaVersion", "sender", "sentAt", "status",
                "type");
        JsonNode engaged = aminaScreen.next("STATUS_CHANGED");
        assertThat(engaged).isNotNull();
        assertThat(engaged.get("status").asText()).isEqualTo("ACTIVE");

        assertThat(post("/api/v1/conversations/" + id + "/messages", aminaLogin(),
                "{\"content\": \"Je n'ai pas fait ces paiements\"}").statusCode()).isEqualTo(201);
        JsonNode toKarim = karimScreen.next("MESSAGE_POSTED");
        // Karim also receives his own first message; skip it.
        if (toKarim != null && "ADVISOR".equals(toKarim.get("sender").asText())) {
            toKarim = karimScreen.next("MESSAGE_POSTED");
        }
        assertThat(toKarim).isNotNull();
        assertThat(toKarim.get("sender").asText()).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("another advisor, another customer: refused on the conversation topic; a supervisor listens")
    void conversationOwnership() throws Exception {
        UUID id = fx.conversation(amina, skill, ConversationStatus.ACTIVE, "0", Instant.now(), karimAdvisor, null);
        String topic = "/topic/conversation/" + id;
        UUID linaUser = fx.user(UserRole.ADVISOR);
        fx.advisor(linaUser, 1, skill);

        assertThat(live.refused(token(linaUser, UserRole.ADVISOR), topic)).as("another advisor").isTrue();
        assertThat(live.refused(token(UserRole.CUSTOMER), topic)).as("another customer").isTrue();
        assertThat(live.refused(token(UserRole.SUPERVISOR), "/topic/conversation/" + UUID.randomUUID()))
                .as("a conversation that does not exist").isTrue();
        assertThat(live.refused(token(UserRole.SUPERVISOR), topic)).as("a supervisor").isFalse();
        assertThat(live.refused(karim(), topic)).as("its advisor").isFalse();
        assertThat(live.refused(aminaLogin(), topic)).as("its customer").isFalse();
    }

    // ------------------------------------------------------------------ queue topic

    @Test
    @DisplayName("an advisor holding the skill sees a contact arrive and leave the queue, with the waiting count")
    void queueMovements() throws Exception {
        Listener queue = live.listen(karim(), "/topic/queue/" + skill);

        HttpResponse<String> opened = post("/api/v1/conversations", token(UserRole.SUPERVISOR),
                "{\"customerId\": \"" + amina + "\", \"skill\": \"" + skill + "\", \"intent\": \"FRAUD\"}");
        assertThat(opened.statusCode()).isEqualTo(201);
        String id = json.readTree(opened.body()).get("id").asText();

        JsonNode arrival = queue.next("CONVERSATION_QUEUED");
        assertThat(arrival).isNotNull();
        assertThat(arrival.get("conversationId").asText()).isEqualTo(id);
        assertThat(arrival.get("waiting").asLong()).isEqualTo(1);
        assertThat(keys(arrival)).containsExactly(
                "conversationId", "occurredAt", "schemaVersion", "skill", "status", "type", "waiting");

        assertThat(post("/api/v1/queues/" + skill + "/next", karim(), "").statusCode()).isEqualTo(200);
        JsonNode departure = queue.next("CONVERSATION_LEFT_QUEUE");
        assertThat(departure).isNotNull();
        assertThat(departure.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(departure.get("waiting").asLong()).isZero();
    }

    @Test
    @DisplayName("a queue topic: refused to an advisor without the skill and to a customer; open to supervisors")
    void queueOwnership() throws Exception {
        String other = fx.skill(60);
        assertThat(live.refused(karim(), "/topic/queue/" + other)).as("a skill Karim does not hold").isTrue();
        assertThat(live.refused(token(UserRole.ADVISOR), "/topic/queue/" + skill)).as("no advisor profile").isTrue();
        assertThat(live.refused(aminaLogin(), "/topic/queue/" + skill)).as("a customer").isTrue();
        assertThat(live.refused(token(UserRole.SUPERVISOR), "/topic/queue/" + other)).isFalse();
        assertThat(live.refused(karim(), "/topic/queue/" + skill)).isFalse();
    }

    // ------------------------------------------------------------------ KPI topic

    @Test
    @DisplayName("the supervisor's KPI banner updates live when a contact arrives")
    void kpiLive() throws Exception {
        Listener banner = live.listen(token(UserRole.SUPERVISOR), "/topic/supervision/kpi");

        assertThat(post("/api/v1/conversations", token(UserRole.SUPERVISOR),
                "{\"customerId\": \"" + amina + "\", \"skill\": \"" + skill + "\"}").statusCode()).isEqualTo(201);

        JsonNode snapshot = banner.next();
        assertThat(snapshot).as("a KPI frame within five seconds").isNotNull();
        assertThat(snapshot.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(snapshot.get("queues")).anySatisfy(q -> {
            assertThat(q.get("skill").asText()).isEqualTo(skill);
            assertThat(q.get("waiting").asLong()).isEqualTo(1);
        });
        assertThat(live.refused(karim(), "/topic/supervision/kpi")).as("advisors do not get the banner").isTrue();
    }
}
