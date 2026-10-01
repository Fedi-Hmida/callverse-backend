package com.callverse.host.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Body returned by a successful {@code POST /api/v1/auth/login}.
 *
 * <p>Part of the published contract: the frontend generates its TypeScript type from the OpenAPI
 * document this record produces, so a field rename here is a breaking change there.
 *
 * <p><strong>{@code role} is a convenience, never an authorisation input.</strong> It lets the
 * client pick which of the four role UIs to render without decoding the token. The server ignores
 * whatever a client sends back and re-reads the role from the verified token on every request.
 *
 * <p><strong>There is no refresh token here, on purpose.</strong> Whether refresh is stateless or
 * backed by a persisted revocable table is an open decision that gates sub-phase 2.4; publishing a
 * field for it now would pre-empt that decision in the contract the frontend is generated from.
 */
@Schema(description = "An access token and the instant it expires")
public record TokenResponse(
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Signed JWT. Send as: Authorization: Bearer <token>",
                        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIuLi4ifQ.signature")
                String token,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "ISO-8601 UTC", example = "2026-09-25T12:00:00Z") Instant expiresAt,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        example = "ADVISOR",
                        allowableValues = {"CUSTOMER", "ADVISOR", "SUPERVISOR", "ADMIN"})
                String role) {}
