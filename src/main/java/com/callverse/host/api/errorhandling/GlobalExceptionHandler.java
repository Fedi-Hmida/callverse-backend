package com.callverse.host.api.errorhandling;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.ApplicationException;
import com.callverse.core.application.exceptions.AuthenticationRequiredException;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
 * <p><strong>401 and 403 have two writers, and one contract.</strong> Denials by the security
 * filter chain happen before the dispatcher and never reach this class; {@link
 * RestAuthenticationEntryPoint} and {@link RestAccessDeniedHandler} write those, through {@link
 * SecurityErrorWriter}, with the same record, codes and messages used here. Denials by
 * {@code @PreAuthorize} are thrown <em>inside</em> the dispatcher and do reach this class — and
 * without the two security handlers below, the catch-all would answer them with a 500 and an
 * ERROR-level stack trace. The single 401 and 403 {@code @ApiResponse} below document both writers.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

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
     * <p>Its 401 is documented by the single 401 {@code @ApiResponse} on
     * {@link #handleAuthentication}.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        log.warn("{} {} -> 401 {}", request.getMethod(), request.getRequestURI(), exception.code());
        return build(HttpStatus.UNAUTHORIZED, exception.code(), exception.getMessage(), request);
    }

    /**
     * A use case needed the caller and there is none — an anonymous request on a route the chain
     * leaves open, such as {@code /api/v1/auth/me} under {@code dev}.
     *
     * <p>Answered with the security chain's own code, message and challenge, not the exception's
     * message, so that a client cannot tell whether the filter chain or a use case refused it.
     */
    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationRequired(
            AuthenticationRequiredException exception, HttpServletRequest request) {
        return unauthenticated(request);
    }

    /**
     * Spring Security refused to authenticate the caller inside MVC.
     *
     * <p>This one annotation documents every 401 the API returns, whichever writer produced it.
     */
    @ApiResponse(
            responseCode = "401",
            description =
                    "Not authenticated. code UNAUTHENTICATED: no bearer token, or one that is"
                            + " expired, malformed or wrongly signed - all indistinguishable, and the"
                            + " signal to refresh or log in again. code INVALID_CREDENTIALS: login"
                            + " refused; an unknown email and a wrong password are deliberately"
                            + " indistinguishable.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(
            AuthenticationException exception, HttpServletRequest request) {
        return unauthenticated(request);
    }

    /**
     * A method-security rule such as {@code @PreAuthorize("hasRole('ADMIN')")} refused the call.
     *
     * <p><strong>This handler is what keeps a routine denial from becoming a 500.</strong>
     * {@code AccessDeniedException} is a {@code RuntimeException}; without a handler this specific,
     * the catch-all below would match it. Spring dispatches to the most specific handler, so
     * declaring it is enough.
     *
     * <p>An <em>anonymous</em> caller gets 401, not 403. Inside the chain,
     * {@code ExceptionTranslationFilter} makes that distinction itself; once the exception is caught
     * here it never reaches that filter, so the distinction is made here instead. Answering an
     * anonymous caller with 403 would tell the frontend "logged in, not allowed", and its refresh
     * logic would never fire.
     */
    @ApiResponse(
            responseCode = "403",
            description =
                    "Authenticated, but not permitted: code ACCESS_DENIED. Re-authenticating will not"
                            + " help, so clients must not refresh on it.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException exception, HttpServletRequest request) {
        if (!TRUST.isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
            return unauthenticated(request);
        }
        return build(
                HttpStatus.FORBIDDEN,
                SecurityErrorWriter.ACCESS_DENIED,
                SecurityErrorWriter.ACCESS_DENIED_MESSAGE,
                request);
    }

    /**
     * A use case refused an action the caller's role allows in general but not on this resource — an
     * advisor resolving a conversation a supervisor now owns. Answered exactly like a
     * {@code @PreAuthorize} denial: same status, code and message, so a client handles one 403. The
     * use case's reason goes to the log, not to the client.
     *
     * <p>Declared separately from {@link ApplicationException}, which it extends, so that Spring
     * dispatches here rather than to the 400 below. Documented by the 403 {@code @ApiResponse} on
     * {@link #handleAccessDenied}.
     */
    @ExceptionHandler(ActionNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleActionNotPermitted(
            ActionNotPermittedException exception, HttpServletRequest request) {
        log.warn("{} {} refused: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return build(
                HttpStatus.FORBIDDEN,
                SecurityErrorWriter.ACCESS_DENIED,
                SecurityErrorWriter.ACCESS_DENIED_MESSAGE,
                request);
    }

    /** Any other application-layer failure: a precondition of the use case was unmet. */
    @ApiResponse(
            responseCode = "400",
            description =
                    "Rejected before any business rule ran: the body is not valid JSON (code"
                            + " MALFORMED_REQUEST), a parameter or field broke a rule (code"
                            + " VALIDATION_FAILED), or a use-case precondition was unmet.",
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

    /**
     * A path variable or query parameter that cannot be converted — {@code /customers/not-a-uuid},
     * {@code ?n=three}. The caller's mistake, so 400, not the catch-all's 500.
     *
     * <p>The message names the parameter and the expected type, never the rejected value: a value
     * echoed back is a value echoed into logs and into whatever displays the error.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        String expected =
                exception.getRequiredType() == null ? "another type" : exception.getRequiredType().getSimpleName();
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "%s must be a %s".formatted(exception.getName(), expected),
                request);
    }

    /** A required query parameter is absent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "%s is required".formatted(exception.getParameterName()),
                request);
    }

    /**
     * The body is not parseable JSON, or does not fit the expected shape at all. Distinct from
     * {@code VALIDATION_FAILED}, which means the body parsed but a field broke a rule.
     *
     * <p>The parser's own message is not returned: it quotes the offending input.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "The request body is not valid JSON for this operation.",
                request);
    }

    /**
     * A real route called with a method it does not support. 405 with an {@code Allow} header naming
     * what it does support, so a client can correct itself; never the catch-all's 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        ResponseEntity<ErrorResponse> response = build(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "%s is not supported on this endpoint.".formatted(request.getMethod()),
                request);
        HttpHeaders headers = new HttpHeaders();
        if (exception.getSupportedHttpMethods() != null) {
            headers.setAllow(exception.getSupportedHttpMethods());
        }
        return ResponseEntity.status(response.getStatusCode()).headers(headers).body(response.getBody());
    }

    /** A body in a content type the route does not read, such as {@code text/plain} on a JSON route. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return build(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "This endpoint accepts application/json.",
                request);
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

    private ResponseEntity<ErrorResponse> unauthenticated(HttpServletRequest request) {
        ResponseEntity<ErrorResponse> response =
                build(
                        HttpStatus.UNAUTHORIZED,
                        SecurityErrorWriter.UNAUTHENTICATED,
                        SecurityErrorWriter.UNAUTHENTICATED_MESSAGE,
                        request);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.WWW_AUTHENTICATE, SecurityErrorWriter.BEARER_CHALLENGE)
                .body(response.getBody());
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
