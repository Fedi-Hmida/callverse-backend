package com.callverse.core.application.features.auth.commands;

import com.callverse.core.domain.enums.UserRole;
import java.time.Instant;

/**
 * The outcome of a successful login.
 *
 * <p>Carries the role so the frontend can choose which of the four role UIs to render without
 * decoding the token. The server never trusts this value on a later request; it re-reads the role
 * from the verified token.
 *
 * <p>There is deliberately no refresh token here. Whether refresh is stateless or backed by a
 * persisted revocable table is an open decision that gates sub-phase 2.4, and inventing a shape now
 * would pre-empt it.
 */
public record LoginResult(String token, Instant expiresAt, UserRole role) {}
