package com.callverse.core.application.features.auth.commands;

import com.callverse.core.application.interfaces.AccessRevocation;
import com.callverse.core.application.interfaces.AuthSessions;
import com.callverse.core.application.interfaces.TokenIssuer;
import java.time.Instant;

public class SessionTokensHandler {
    public record Tokens(String token, Instant expiresAt, String role, String refreshToken, Instant refreshExpiresAt) {
        @Override public String toString() { return "Tokens[credentials=redacted]"; }
    }
    private final AuthSessions sessions;
    private final TokenIssuer tokens;
    private final AccessRevocation live;
    public SessionTokensHandler(AuthSessions sessions, TokenIssuer tokens, AccessRevocation live) {
        this.sessions=sessions; this.tokens=tokens; this.live=live;
    }
    public Tokens start(String authenticatedEmail) { return issue(sessions.start(authenticatedEmail)); }
    public Tokens refresh(String refreshToken) { return issue(sessions.rotate(refreshToken)); }
    public void logout(String refreshToken) { sessions.revoke(refreshToken).ifPresent(live::revoke); }
    private Tokens issue(AuthSessions.Grant grant) {
        var user=grant.principal();
        var access=tokens.issue(user.userId(),user.email(),user.role(),grant.sessionId());
        return new Tokens(access.value(),access.expiresAt(),user.role().name(),grant.refreshToken(),grant.expiresAt());
    }
}
