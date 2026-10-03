package com.callverse.infrastructure.security;

import com.callverse.core.application.interfaces.AccessRevocation;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
 * How long each live session may keep receiving, and the guard that enforces it on the way out.
 *
 * <p>A WebSocket outlives the request that opened it. A session stops receiving when its token
 * expires, and at once when an administrator blocks its account or changes its role
 * ({@link #revoke}). As an outbound interceptor, this drops every MESSAGE frame addressed to such a
 * session; the client notices the silence, and its next SUBSCRIBE is refused until it reconnects
 * with a valid token for an account that may act.
 *
 * <p>Entries are added on CONNECT and removed when the session ends, so the maps hold open
 * sessions only. Revoked sessions are silenced, not closed: the client sees no more frames and its
 * next SUBSCRIBE is refused, which closes it. The registry is in memory, so revocation reaches the
 * sessions of this instance only — this project runs one backend instance.
 */
@Component
public class StompSessionRegistry implements ChannelInterceptor, AccessRevocation {

    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
    private final Map<String, UUID> owners = new ConcurrentHashMap<>();
    private final Clock clock;

    public StompSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    void register(String sessionId, UUID userId, Instant expiresAt) {
        if (sessionId != null) {
            expiries.put(sessionId, expiresAt);
            owners.put(sessionId, userId);
        }
    }

    void unregister(String sessionId) {
        if (sessionId != null) {
            expiries.remove(sessionId);
            owners.remove(sessionId);
        }
    }

    /** True for a session that never authenticated, whose token has expired, or that was revoked. */
    boolean isExpiredOrUnknown(String sessionId) {
        Instant expiry = sessionId == null ? null : expiries.get(sessionId);
        return expiry == null || !clock.instant().isBefore(expiry);
    }

    /** Every open session of this account stops receiving now. */
    @Override
    public void revoke(UUID userId) {
        owners.forEach((sessionId, owner) -> {
            if (owner.equals(userId)) {
                // computeIfPresent: a session that disconnected meanwhile is not resurrected as an entry.
                expiries.computeIfPresent(sessionId, (id, expiry) -> Instant.EPOCH);
            }
        });
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        expiries.remove(event.getSessionId());
        owners.remove(event.getSessionId());
    }

    /** Outbound: nothing reaches a session whose token has expired or whose access was revoked. */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE
                && isExpiredOrUnknown(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()))) {
            return null;
        }
        return message;
    }
}
