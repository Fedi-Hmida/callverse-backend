package com.callverse.host.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Body returned by {@code GET /api/v1/health/status}.
 *
 * <p>Part of the published contract: the Angular client generates its TypeScript type from the
 * OpenAPI document this record produces, so a field rename here is a breaking change there.
 *
 * <p>{@code status} is a {@code String} rather than the domain enum on purpose. Serialising the
 * enum directly would publish a domain type on the wire and make every new enum constant an
 * implicit API change.
 */
@Schema(description = "Business-level status of the CallVerse platform")
public record HealthStatusResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "callverse-backend") String service,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "0.1.0-SNAPSHOT") String version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "UP", allowableValues = {"UP", "DEGRADED", "DOWN"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "ISO-8601 UTC", example = "2026-09-14T08:31:07.412Z") Instant timestamp,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "dev") String profile) {}
