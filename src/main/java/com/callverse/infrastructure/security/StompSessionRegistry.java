package com.callverse.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * How long each live session's token is good for, and the guard that enforces it on the way out.
 *
 * <p>A WebSocket outlives the request that opened it, and a token is the only revocation this
 * platform has: the principal is never re-read from the database. So a session whose token has
 * expired must stop <em>receiving</em>, not only stop subscribing. As an outbound interceptor, this
 * drops every MESSAGE frame addressed to an expired session; the client notices the silence, and its
 * next SUBSCRIBE is refused until it reconnects with a fresh token.
 *
 * <p>Entries are added on CONNECT and removed when the session ends, so the map holds open
 * sessions only.
 */
@Component
public class StompSessionRegistry implements ChannelInterceptor {

    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
    private final Clock clock;

    public StompSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    void register(String sessionId, Instant expiresAt) {
        if (sessionId != null) {
            expiries.put(sessionId, expiresAt);
        }
    }

    /** True for a session that never authenticated, or whose token has expired since. */
    boolean isExpiredOrUnknown(String sessionId) {
        Instant expiry = sessionId == null ? null : expiries.get(sessionId);
        return expiry == null || !clock.instant().isBefore(expiry);
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        expiries.remove(event.getSessionId());
    }

    /** Outbound: nothing reaches a session whose token has expired. */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE
                && isExpiredOrUnknown(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()))) {
            return null;
        }
        return message;
    }
}
