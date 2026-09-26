package com.callverse.host.api.errorhandling;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes the {@link ErrorResponse} envelope for denials that happen before the dispatcher.
 *
 * <p><strong>Why a second writer exists at all.</strong> The security filter chain runs before
 * {@code DispatcherServlet}, so a refusal there never reaches {@link GlobalExceptionHandler}. Left
 * alone, Spring answers with an empty body. This class makes those refusals speak the same envelope.
 *
 * <p><strong>How it stays identical to the other writer.</strong> It serialises the very same
 * {@link ErrorResponse} record, with the application's own {@link ObjectMapper} — the one Spring MVC
 * uses — and the same injected {@link Clock}. Field order, timestamp format and content type
 * therefore cannot drift, and {@code SecurityErrorContractTest} compares the two writers' output
 * field for field to prove it.
 *
 * <p>It also owns the codes and messages for the two auth outcomes, so that the MVC-side handlers
 * return exactly the same words as the chain.
 */
final class SecurityErrorWriter {

    static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    /** Deliberately says nothing about which check failed: see {@code JwtAuthenticationFilter}. */
    static final String UNAUTHENTICATED_MESSAGE = "Missing, invalid or expired credentials.";

    static final String ACCESS_DENIED = "ACCESS_DENIED";
    static final String ACCESS_DENIED_MESSAGE = "You are not permitted to perform this operation.";

    /** RFC 6750 §3: a 401 names the scheme. No {@code error=} detail, which would distinguish failures. */
    static final String BEARER_CHALLENGE = "Bearer";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    SecurityErrorWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param challenge the {@code WWW-Authenticate} value, or null to send none — {@code /internal}
     *     accepts no standard scheme, so it sends none rather than a misleading {@code Bearer}
     */
    void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            String challenge)
            throws IOException {
        if (challenge != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ErrorResponse(clock.instant(), status.value(), code, message, request.getRequestURI()));
    }
}
