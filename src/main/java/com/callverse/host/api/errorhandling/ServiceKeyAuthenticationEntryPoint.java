package com.callverse.host.api.errorhandling;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401 {@code UNAUTHENTICATED} for {@code /internal/**}: the same envelope, code and message as every
 * other 401, but without {@code WWW-Authenticate: Bearer}, because a bearer token is not what that
 * route accepts. Advertising it would point a caller at the wrong credential.
 *
 * <p>Named, because two entry points now exist: the internal chain asks for this one by name and
 * everything else receives {@link RestAuthenticationEntryPoint}, which is {@code @Primary}.
 */
@Component("serviceKeyAuthenticationEntryPoint")
@Slf4j
public class ServiceKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorWriter writer;

    public ServiceKeyAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
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
                null);
    }
}
