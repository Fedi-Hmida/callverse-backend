package com.callverse.host.api.errorhandling;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The single error envelope every failing request returns, regardless of which layer raised the
 * failure or whether it was expected.
 *
 * <p>Agreed with the Angular team and the Python AI service: one shape means one error-handling
 * path in each client. The {@code code} field is the part clients branch on. {@code message} is for
 * humans and may be reworded or translated without notice, so treating it as a contract is a bug.
 *
 * @param timestamp when the failure was observed, ISO-8601 UTC
 * @param status the HTTP status code, repeated in the body so that logs and captured payloads are
 *     self-describing without the response headers
 * @param code stable machine-readable identifier, for example {@code RESOURCE_NOT_FOUND}
 * @param message human-readable explanation, never containing a stack trace or credentials
 * @param path the request path that failed
 */
@Schema(description = "Standard error envelope returned by every CallVerse endpoint")
public record ErrorResponse(
        @Schema(example = "2026-09-14T08:31:07.412Z") Instant timestamp,
        @Schema(example = "404") int status,
        @Schema(example = "RESOURCE_NOT_FOUND") String code,
        @Schema(example = "Customer 'a3f1...' was not found") String message,
        @Schema(example = "/api/v1/customers/a3f1") String path) {}
