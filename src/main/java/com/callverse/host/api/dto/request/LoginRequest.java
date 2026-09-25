package com.callverse.host.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/auth/login}.
 *
 * <p>The first occupant of this package, and the first request DTO in the repository.
 *
 * <p><strong>Validation is deliberately shallow.</strong> Both fields must be present and
 * non-blank, and nothing more: no email-format check, no length or complexity rule. A stricter
 * rejection would answer a different question than the caller asked — the endpoint's only job is to
 * say whether these credentials are accepted, and a malformed address is simply not accepted. It
 * would also hand an attacker a cheap distinction between "not a real address" and "not a real
 * account".
 */
@Schema(description = "Credentials submitted for authentication")
public record LoginRequest(
        @NotBlank @Schema(example = "advisor@callverse.local", requiredMode = Schema.RequiredMode.REQUIRED)
                String email,
        @NotBlank
                @Schema(
                        description = "Never logged, never echoed back",
                        example = "CallVerse!Dev2026",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String password) {}
