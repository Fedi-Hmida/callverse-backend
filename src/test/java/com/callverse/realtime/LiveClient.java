package com.callverse.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * A browser-like STOMP client for the live tests: connect with a token, subscribe, collect JSON
 * frames, and tell a refused subscription (the server's "Access denied" ERROR frame) from anything
 * else. Close it after each test.
 */
final class LiveClient implements AutoCloseable {

    private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    private final String url;
    private final List<StompSession> sessions = new ArrayList<>();

    LiveClient(int port) {
        this.url = "ws://localhost:" + port + "/ws";
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
    }

    /** How the server treated one session: connected, then possibly refused with an ERROR frame. */
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

    /** A connected listener: its session, its probe, and every JSON frame it has received. */
    record Listener(StompSession session, Probe probe, BlockingQueue<JsonNode> frames) {

        JsonNode next() throws InterruptedException {
            return frames.poll(5, TimeUnit.SECONDS);
        }

        /** The next frame of {@code type}, skipping others; null after five seconds. */
        JsonNode next(String type) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode frame = frames.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(frame.path("type").asText())) {
                    return frame;
                }
            }
            return null;
        }

        JsonNode quietFor(long millis) throws InterruptedException {
            return frames.poll(millis, TimeUnit.MILLISECONDS);
        }
    }

    /** Connects with {@code token} and subscribes to {@code destination}. */
    Listener listen(String token, String destination) throws Exception {
        Probe probe = new Probe();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync(url, new WebSocketHttpHeaders(), connect, probe).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.add((JsonNode) payload);
            }
        });
        pause(400); // SUBSCRIBE is asynchronous: give the server time to accept or refuse it
        return new Listener(session, probe, frames);
    }

    /**
     * Whether the server refused this subscription with its "Access denied" ERROR frame — so that a
     * broken socket cannot pass for a refusal.
     */
    boolean refused(String token, String destination) throws Exception {
        Listener listener = listen(token, destination);
        long deadline = System.currentTimeMillis() + 1500;
        while (!listener.probe().refused.get() && System.currentTimeMillis() < deadline) {
            pause(50);
        }
        if (!listener.probe().refused.get()) {
            return false;
        }
        if (!"Access denied".equals(listener.probe().errorMessage)) {
            throw new AssertionError("refused, but not by the interceptor: " + listener.probe().errorMessage);
        }
        return true;
    }

    /** Whether the server refuses to even open a session for this token (closed at CONNECT). */
    boolean connectRefused(String token) {
        Probe probe = new Probe();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        try {
            StompSession session = client.connectAsync(url, new WebSocketHttpHeaders(), connect, probe)
                    .get(5, TimeUnit.SECONDS);
            sessions.add(session);
        } catch (Exception closedAtConnect) {
            return true;
        }
        long deadline = System.currentTimeMillis() + 1500;
        while (!probe.refused.get() && System.currentTimeMillis() < deadline) {
            pause(50);
        }
        return probe.refused.get();
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
        client.stop();
    }
}
