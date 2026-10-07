package com.callverse.realtime;

import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.conversation.ConversationFixtures;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.callverse.realtime.LiveClient.Listener;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Blocking an account cuts its live sessions at once, not when its token expires: the supervisor's
 * open screen goes quiet, and reconnecting with the same token is refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class StompAccessRevocationTest extends AbstractPersistenceTest {

    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private final HttpClient http = HttpClient.newHttpClient();
    private LiveClient live;
    private ConversationFixtures fx;

    @BeforeEach
    void setUp() {
        live = new LiveClient(port);
        fx = new ConversationFixtures(em, tx);
    }

    @AfterEach
    void tearDown() {
        live.close();
    }

    private int post(String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("a blocked supervisor's open KPI screen receives nothing more, and the same token cannot reconnect")
    void blockCutsTheLiveSession() throws Exception {
        String skill = fx.skill(60);
        UUID customer = fx.customer(null, ChurnRisk.LOW, CustomerSegment.MASS);
        String admin = validToken(UUID.randomUUID(), "nadia@bank.test", UserRole.ADMIN);
        UUID sarahId = UUID.randomUUID();
        String sarah = validToken(sarahId, "sarah@bank.test", UserRole.SUPERVISOR);
        String openContact = "{\"customerId\": \"" + customer + "\", \"skill\": \"" + skill + "\"}";

        Listener screen = live.listen(sarah, "/topic/supervision/kpi");
        Listener colleague = live.listen(validToken(UUID.randomUUID(), "leo@bank.test", UserRole.SUPERVISOR),
                "/topic/supervision/kpi");
        assertThat(post("/api/v1/conversations", admin, openContact)).isEqualTo(201);
        assertThat(screen.next()).as("before the block, the banner updates").isNotNull();

        assertThat(post("/api/v1/admin/users/" + sarahId + "/block", admin, "")).isEqualTo(200);
        assertThat(post("/api/v1/conversations", admin, openContact)).isEqualTo(201);

        colleague.next();   // the first banner
        assertThat(colleague.next()).as("the second banner is really published: a colleague receives it").isNotNull();
        assertThat(screen.quietFor(1500)).as("after the block, nothing reaches the open session").isNull();
        assertThat(live.connectRefused(sarah)).as("reconnecting with the same token is refused at CONNECT").isTrue();
    }
}
