package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistent, rotating refresh sessions. Only hashes are retained by the adapter. */
public interface AuthSessions {
    record Grant(UUID sessionId, AuthenticatedPrincipal principal, String refreshToken, Instant expiresAt) {
        @Override public String toString() { return "Grant[sessionId="+sessionId+", refreshToken=redacted]"; }
    }
    Grant start(String authenticatedEmail);
    Grant rotate(String refreshToken);
    Optional<UUID> revoke(String refreshToken);
    boolean active(UUID sessionId, UUID userId);
}
