package com.callverse.host.api.errorhandling;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 403 {@code ACCESS_DENIED}, in the standard envelope, for a caller the chain <em>did</em> identify
 * but whose request a URL rule refuses.
 *
 * <p>Spring only calls this for an authenticated caller; an anonymous one is sent to
 * {@link RestAuthenticationEntryPoint} instead, which is what keeps 401 and 403 apart. Denials
 * raised by {@code @PreAuthorize} inside MVC never come here — {@link GlobalExceptionHandler}
 * answers those with the same code and message. See {@link RestAuthenticationEntryPoint} for why
 * this class sits in {@code host}.
 */
@Component
@Slf4j
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorWriter writer;

    public RestAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.writer = new SecurityErrorWriter(objectMapper, clock);
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        log.warn(
                "{} {} -> 403 {}",
                request.getMethod(),
                request.getRequestURI(),
                SecurityErrorWriter.ACCESS_DENIED);
        writer.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                SecurityErrorWriter.ACCESS_DENIED,
                SecurityErrorWriter.ACCESS_DENIED_MESSAGE);
    }
}
