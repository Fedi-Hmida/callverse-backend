package com.callverse.host.api.errorhandling;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401 {@code UNAUTHENTICATED}, in the standard envelope, for every caller the security chain could
 * not identify: no token on a route that needs one, or a token the JWT filter refused.
 *
 * <p>Without this bean Spring falls back to {@code Http403ForbiddenEntryPoint} and answers
 * <em>403 with an empty body</em> for a caller who simply is not logged in — which the frontend
 * cannot parse and which never triggers its refresh logic, since that fires on 401.
 *
 * <p><strong>Why it lives in {@code host} and not {@code infrastructure.security}.</strong> It writes
 * the frozen {@link ErrorResponse}, which is a {@code host} type. Placing it in
 * {@code infrastructure} would make {@code infrastructure} import {@code host}; the cycle rule would
 * then forbid {@code host} from ever importing {@code infrastructure}. Here it shares the record
 * with {@link GlobalExceptionHandler}, and {@code SecurityConfiguration} receives it by its Spring
 * interface type without naming this class.
 */
@Component
@Primary // the default entry point; the withdrawn /internal chain declared its own
@Slf4j
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorWriter writer;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        this.writer = new SecurityErrorWriter(objectMapper, clock);
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.warn(
                "{} {} -> 401 {}",
                request.getMethod(),
                request.getRequestURI(),
                SecurityErrorWriter.UNAUTHENTICATED);
        writer.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                SecurityErrorWriter.UNAUTHENTICATED,
                SecurityErrorWriter.UNAUTHENTICATED_MESSAGE,
                SecurityErrorWriter.BEARER_CHALLENGE);
    }
}
