package com.callverse.realtime;

import static com.callverse.auth.AuthenticatedRequests.tokenExpiringIn;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.SupervisionAlert;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The live channel attacked with raw STOMP frames — exactly what a hostile client can send, without
 * a STOMP library's good manners in the way.
 *
 * <p>Every frame here is written by hand: a MESSAGE frame forged by a client that never connected,
 * the STOMP 1.2 {@code STOMP} alias for CONNECT without a token, a wildcard subscription, and a
 * session that outlives its token. The alert's wire format is checked here too, as the broker's own
 * converter produces it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class StompHardeningTest extends AbstractPersistenceTest {

    private static final String ALERTS = "/topic/supervision/alerts";

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired RealtimeEventPublisher publisher;

    private final List<WebSocketSession> sessions = new ArrayList<>();

    @AfterEach
    void close() {
        for (WebSocketSession session : sessions) {
            try {
                session.close();
            } catch (Exception ignored) {
                // already closed by the server, which is often the point of the test
            }
        }
    }

    /** A raw WebSocket session: frames go out as written, frames come back as text. */
    private final class Raw extends TextWebSocketHandler {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        volatile boolean closed;
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed = true;
        }

        void send(String frame) throws Exception {
            session.sendMessage(new TextMessage(frame));
        }

        /** The next frame whose command is {@code command}, or null after the timeout. */
        String next(String command, long millis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                String frame = frames.poll(50, TimeUnit.MILLISECONDS);
                if (frame != null && frame.startsWith(command)) {
                    return frame;
                }
            }
            return null;
        }
    }

    private Raw open() throws Exception {
        Raw raw = new Raw();
        raw.session = new StandardWebSocketClient()
                .execute(raw, new WebSocketHttpHeaders(), java.net.URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
        sessions.add(raw.session);
        return raw;
    }

    private static String frame(String command, String... headers) {
        return command + "\n" + String.join("\n", headers) + "\n\n\u0000";
    }

    private static String token(UserRole role) {
        return validToken(UUID.randomUUID(), role.name().toLowerCase() + "@callverse.local", role);
    }

    private Raw connected(String token) throws Exception {
        Raw raw = open();
        raw.send(frame("CONNECT", "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token));
        assertThat(raw.next("CONNECTED", 5000)).as("CONNECTED").isNotNull();
        return raw;
    }

    private Raw supervisorListening() throws Exception {
        Raw supervisor = connected(token(UserRole.SUPERVISOR));
        supervisor.send(frame("SUBSCRIBE", "id:sub-0", "destination:" + ALERTS));
        Thread.sleep(300);
        assertThat(supervisor.next("ERROR", 100)).as("the supervisor's own subscription is accepted").isNull();
        return supervisor;
    }

    @Test
    @DisplayName("a MESSAGE frame from a client that never connected is refused and never reaches a supervisor")
    void forgedMessageFrameIsRefused() throws Exception {
        Raw supervisor = supervisorListening();

        Raw forger = open();
        forger.send(frame("MESSAGE", "destination:" + ALERTS, "content-type:application/json",
                "subscription:sub-0", "message-id:forged")
                .replace("\n\n\u0000", "\n\n{\"type\":\"ESCALATION_RAISED\",\"forged\":true}\u0000"));

        assertThat(forger.next("ERROR", 3000)).as("the forger gets an ERROR frame").isNotNull();
        assertThat(supervisor.next("MESSAGE", 1500)).as("nothing reaches the supervisor").isNull();
    }

    @Test
    @DisplayName("a MESSAGE frame from a connected staff member is refused too: only the server publishes")
    void connectedClientCannotSendMessageFrames() throws Exception {
        Raw supervisor = supervisorListening();
        Raw admin = connected(token(UserRole.ADMIN));

        admin.send(frame("MESSAGE", "destination:" + ALERTS, "content-type:application/json",
                "subscription:sub-0", "message-id:forged")
                .replace("\n\n\u0000", "\n\n{\"forged\":true}\u0000"));

        assertThat(admin.next("ERROR", 3000)).isNotNull();
        assertThat(supervisor.next("MESSAGE", 1500)).isNull();
    }

    @Test
    @DisplayName("the STOMP 1.2 'STOMP' command is a CONNECT: without a token it is refused")
    void stompAliasNeedsAToken() throws Exception {
        Raw raw = open();
        raw.send(frame("STOMP", "accept-version:1.2", "host:localhost"));

        assertThat(raw.next("CONNECTED", 2000)).as("no CONNECTED without a token").isNull();
        assertThat(raw.frames.isEmpty() && !raw.closed ? raw.next("ERROR", 1000) : "refused").isNotNull();
    }

    @Test
    @DisplayName("the 'STOMP' alias with a valid token connects, like CONNECT")
    void stompAliasWithATokenConnects() throws Exception {
        Raw raw = open();
        raw.send(frame("STOMP", "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token(UserRole.SUPERVISOR)));

        assertThat(raw.next("CONNECTED", 5000)).isNotNull();
    }

    @Test
    @DisplayName("wildcard subscriptions are refused: one subscription may not cover every conversation")
    void wildcardSubscriptionsAreRefused() throws Exception {
        for (String destination : List.of("/topic/conversation/*", "/topic/queue/**", "/topic/runs/{id}", "/topic/queue/CA?DS")) {
            Raw advisor = connected(token(UserRole.SUPERVISOR));
            advisor.send(frame("SUBSCRIBE", "id:w", "destination:" + destination));
            assertThat(advisor.next("ERROR", 3000)).as("wildcard %s", destination).isNotNull();
        }
    }

    @Test
    @DisplayName("a session whose token has expired stops receiving alerts, while a fresh one still does")
    void expiredSessionStopsReceiving() throws Exception {
        Raw shortLived = connected(tokenExpiringIn(Duration.ofSeconds(2), UUID.randomUUID(), "s@callverse.local",
                UserRole.SUPERVISOR));
        shortLived.send(frame("SUBSCRIBE", "id:s", "destination:" + ALERTS));
        Raw fresh = supervisorListening();
        Thread.sleep(2500);

        publisher.publishSupervisionAlert(SupervisionAlert.escalationRaised(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()));

        assertThat(fresh.next("MESSAGE", 3000)).as("a valid session still receives").isNotNull();
        assertThat(shortLived.next("MESSAGE", 1000)).as("an expired session receives nothing").isNull();
    }

    @Test
    @DisplayName("on the wire, an alert has all eight fields (nulls included) and an ISO-8601 time")
    void wireFormatIsTheContract() throws Exception {
        Raw supervisor = supervisorListening();
        publisher.publishSupervisionAlert(SupervisionAlert.cardBlockedForFraud(
                UUID.randomUUID(), UUID.randomUUID(), "4242", Instant.parse("2026-10-01T16:53:00.123456Z")));

        String message = supervisor.next("MESSAGE", 3000);
        assertThat(message).isNotNull();
        JsonNode body = objectMapper.readTree(message.substring(message.indexOf("\n\n") + 2).replace("\u0000", ""));
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "schemaVersion", "type", "occurredAt", "customerId", "conversationId", "escalationId", "cardId",
                "cardLast4");
        assertThat(body.get("conversationId").isNull()).isTrue();
        assertThat(body.get("occurredAt").asText()).isEqualTo("2026-10-01T16:53:00.123456Z");
    }
}
