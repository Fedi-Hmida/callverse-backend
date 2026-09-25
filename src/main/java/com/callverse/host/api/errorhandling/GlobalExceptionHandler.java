package com.callverse.host.api.errorhandling;

import com.callverse.core.application.exceptions.ApplicationException;
import com.callverse.core.application.exceptions.InvalidCredentialsException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.domain.exceptions.DomainException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into the {@link ErrorResponse} envelope.
 *
 * <p>This is the only place in the system that decides an HTTP status. Controllers do not catch,
 * and use cases do not know what a status code is; they throw vocabulary from
 * {@code core.domain.exceptions} and {@code core.application.exceptions}, and the mapping from that
 * vocabulary to HTTP lives here, where it can be read in one screen.
 *
 * <p>The catch-all at the bottom is deliberate and its logging asymmetry is the point: expected
 * failures are logged at WARN without a stack trace, because a 404 is not an incident and a
 * thousand of them should not bury a real one. Anything unanticipated is logged at ERROR with its
 * stack trace, and the client is told nothing beyond a correlation-free generic message, because an
 * exception message from an unexpected failure is exactly where connection strings and internal
 * hostnames leak into a response body.
 *
 * <p><strong>The {@code @ApiResponse} annotations below are what publish this envelope.</strong>
 * springdoc reads them from the advice and attaches them to <em>every</em> operation in the
 * document, so a new controller inherits the documented failure modes without annotating anything.
 * They are declared here rather than on the controllers for the same reason the mapping itself is:
 * this class is the single authority on what a failure looks like, and a copy on each controller
 * would drift from it. Keep an annotation and its handler in step — the annotation is the contract
 * the Angular client is generated from, so a status documented here and not returned is a lie the
 * compiler cannot catch.
 *
 * <p><strong>401 and 403 are deliberately absent.</strong> They never reach this class: Spring
 * Security rejects at the filter chain, before the dispatcher, and returns an empty body rather
 * than this envelope. Documenting them here would advertise a shape the API does not produce. Once
 * Phase 2 adds an {@code AuthenticationEntryPoint} that writes an {@link ErrorResponse}, add them.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final Clock clock;

    /** A use case referenced something that does not exist. */
    @ApiResponse(
            responseCode = "404",
            description = "No such route, or a referenced resource does not exist.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, exception.code(), exception.getMessage(), request);
    }

    /**
     * A route that does not exist. Spring Boot 3.2+ raises this rather than serving a whitelabel
     * page, which is what lets an unknown path return the same envelope as every other failure
     * instead of an HTML body the Angular client cannot parse.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException exception, HttpServletRequest request) {
        return build(
                HttpStatus.NOT_FOUND,
                "ENDPOINT_NOT_FOUND",
                "No endpoint %s %s".formatted(request.getMethod(), request.getRequestURI()),
                request);
    }

    /**
     * Authentication was refused.
     *
     * <p>Declared separately from {@link ApplicationException}, which it extends, because Spring
     * dispatches to the most specific handler and this one must answer 401 rather than 400: the
     * request was well-formed, the credentials were not accepted.
     *
     * <p>Logged at WARN without the email. A log line naming the address that failed is a list of
     * valid accounts for anyone who reads the logs, which defeats the point of returning an
     * indistinguishable error to the caller.
     *
     * <p>This covers only failures raised inside a use case. Denials produced by the security
     * filter chain never reach this class — the chain runs before the dispatcher — and giving those
     * the same envelope is sub-phase 2.3.
     */
    @ApiResponse(
            responseCode = "401",
            description =
                    "Authentication failed. An unknown email and a wrong password are deliberately"
                            + " indistinguishable: both return code INVALID_CREDENTIALS.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        log.warn("{} {} -> 401 {}", request.getMethod(), request.getRequestURI(), exception.code());
        return build(HttpStatus.UNAUTHORIZED, exception.code(), exception.getMessage(), request);
    }

    /** Any other application-layer failure: a precondition of the use case was unmet. */
    @ApiResponse(
            responseCode = "400",
            description =
                    "Rejected before any business rule ran: Bean Validation failed"
                            + " (code VALIDATION_FAILED) or a use-case precondition was unmet.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ErrorResponse> handleApplication(
            ApplicationException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, exception.code(), exception.getMessage(), request);
    }

    /**
     * A business rule refused the operation. 409 rather than 400: the request was well-formed and
     * the caller did nothing wrong syntactically, but the current state of the business forbids it.
     */
    @ApiResponse(
            responseCode = "409",
            description =
                    "A business rule refused the operation. The request was well-formed; the"
                            + " current state of the business forbids it.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(
            DomainException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, exception.code(), exception.getMessage(), request);
    }

    /** Bean Validation rejected a request body at the edge. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String details =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> "%s %s".formatted(error.getField(), error.getDefaultMessage()))
                        .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", details, request);
    }

    /** Anything not anticipated above. */
    @ApiResponse(
            responseCode = "500",
            description =
                    "Unexpected failure. message is deliberately generic and carries no internal"
                            + " detail; see the server log for the stack trace.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                // Deliberately not exception.getMessage(): see the class javadoc.
                "An unexpected error occurred.",
                request);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        if (status.is4xxClientError()) {
            log.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), status.value(), code);
        }
        return ResponseEntity.status(status)
                .body(new ErrorResponse(
                        clock.instant(), status.value(), code, message, request.getRequestURI()));
    }
}
