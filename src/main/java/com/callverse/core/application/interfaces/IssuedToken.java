package com.callverse.core.application.interfaces;

import java.time.Instant;

/**
 * An access token and the instant it stops being valid.
 *
 * <p>The expiry travels with the token so the client need not decode the token to learn it. That is
 * a convenience for the frontend, not a security control — the server re-checks expiry on every
 * request regardless of what any client believes.
 *
 * @param value the encoded token, opaque to this layer
 * @param expiresAt UTC instant after which the token must be rejected
 */
public record IssuedToken(String value, Instant expiresAt) {}
