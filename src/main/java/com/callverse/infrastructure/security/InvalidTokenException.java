package com.callverse.infrastructure.security;

import org.springframework.security.core.AuthenticationException;

/**
 * A bearer token was presented and refused.
 *
 * <p>The {@link Reason} is for the server log only. The caller receives the same 401, code and
 * message whatever the reason, because a response that distinguishes "expired" from "bad signature"
 * from "unknown account" tells an attacker which of their guesses came close. The log line carries
 * the reason so that an operator can still tell a clock problem from a forgery attempt.
 *
 * <p>Extends Spring's {@link AuthenticationException} so it can be handed straight to the
 * {@code AuthenticationEntryPoint}, which is what writes the response.
 */
class InvalidTokenException extends AuthenticationException {

    enum Reason {
        /** Not three base64url segments of JSON, or an empty token. */
        MALFORMED,
        /** Signed correctly, but {@code exp} has passed according to the application clock. */
        EXPIRED,
        /** The signature does not match: edited payload, or a key this application does not hold. */
        BAD_SIGNATURE,
        /** Any {@code alg} other than HS512, including {@code none}. */
        UNSUPPORTED_ALGORITHM,
        /** Signed correctly, but a required claim is missing or unparseable. */
        INVALID_CLAIMS,
        /** A valid token for an account that does not exist. */
        ACCOUNT_UNKNOWN,
        /** A valid token for an account an administrator has blocked. */
        ACCOUNT_BLOCKED,
        /** A valid token claiming a role the account no longer holds. */
        ROLE_CHANGED
    }

    private final Reason reason;

    InvalidTokenException(Reason reason, Throwable cause) {
        super("Bearer token refused: " + reason, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
