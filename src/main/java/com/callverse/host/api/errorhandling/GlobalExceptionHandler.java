package com.callverse.host.api.errorhandling;

import com.callverse.core.application.exceptions.ApplicationException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.domain.exceptions.DomainException;
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
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final Clock clock;

    /** A use case referenced something that does not exist. */
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

    /** Any other application-layer failure: a precondition of the use case was unmet. */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ErrorResponse> handleApplication(
            ApplicationException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, exception.code(), exception.getMessage(), request);
    }

    /**
     * A business rule refused the operation. 409 rather than 400: the request was well-formed and
     * the caller did nothing wrong syntactically, but the current state of the business forbids it.
     */
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
