package com.callverse.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the Python AI service on {@code /internal/**} by its shared key.
 *
 * <p><strong>A distinct scheme, not a JWT variant.</strong> The AI service is not a user: it has no
 * account, no email and no role. Its principal is the fixed name {@link #PRINCIPAL} with the single
 * authority {@link #AUTHORITY}, and {@code CurrentPrincipalProvider} reports no user for it — so a
 * handler can never mistake the AI for a customer. Conversely, the JWT filter is not in this chain,
 * so a user's token opens nothing here, however privileged the user.
 *
 * <p><strong>Outcomes.</strong> No {@code X-Internal-Key} header: the request continues anonymous and
 * the chain's rule refuses it with 401. A key that does not match: refused at once with the same 401,
 * and a WARN line that names the route but never the presented value. Only the header is read; a key
 * in the query string would land in access logs and browser history.
 *
 * <p>Not a Spring bean, for the same reason as {@code JwtAuthenticationFilter}: Boot would also
 * register it on the servlet container, outside the chain.
 */
@Slf4j
class ServiceKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Key";
    static final String PRINCIPAL = "ai-service";
    static final String AUTHORITY = "SERVICE_AI";

    private final ServiceKeyVerifier verifier;
    private final AuthenticationEntryPoint entryPoint;

    ServiceKeyAuthenticationFilter(ServiceKeyVerifier verifier, AuthenticationEntryPoint entryPoint) {
        this.verifier = verifier;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null) {
            chain.doFilter(request, response);
            return;
        }

        if (!verifier.matches(presented)) {
            SecurityContextHolder.clearContext();
            log.warn("Service key refused on {} {}", request.getMethod(), request.getRequestURI());
            entryPoint.commence(request, response, new BadCredentialsException("Service key refused"));
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        SecurityContextHolder.setContext(context);

        chain.doFilter(request, response);
    }
}
