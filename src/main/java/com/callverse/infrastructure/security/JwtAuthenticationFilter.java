package com.callverse.infrastructure.security;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns {@code Authorization: Bearer <token>} into an authenticated principal.
 *
 * <p><strong>Three outcomes, and only one of them is a rejection.</strong>
 *
 * <ul>
 *   <li><em>No {@code Authorization} header, or a scheme other than Bearer</em> — the filter does
 *       nothing and the request continues anonymous. Whether anonymous is acceptable is the chain's
 *       authorization decision, not this filter's.
 *   <li><em>A bearer token that verifies</em> — the {@code SecurityContext} receives the principal
 *       and a single authority {@code ROLE_<role>}, which is the convention
 *       {@code hasRole('ADVISOR')} expects, so 2.5's annotations need no adapter.
 *   <li><em>A bearer token that does not verify</em> — refused at once with 401 through the entry
 *       point, even on a route that would admit an anonymous caller. A client that presents a
 *       credential and has it silently ignored cannot tell that its session has expired, and the
 *       frontend's refresh logic triggers on exactly that 401. Every refusal reason produces the
 *       same response; the reason is logged at WARN, without the email.
 * </ul>
 *
 * <p><strong>No database lookup, and the exposure that buys.</strong> The principal is built from
 * the verified claims alone. Re-reading {@code app_user} on every request would cost a round trip
 * to Neon per call against a pool capped at 8 connections. The price is explicit: <strong>an account
 * deactivated, deleted or demoted after its token was issued keeps its old access until that token
 * expires</strong> — {@code jwt.expiration-ms}, one hour by default — and there is no revocation
 * mechanism of any kind. That window closes with sub-phase 2.4 (short-lived access tokens behind a
 * revocable refresh) or with a cached {@code active} check here; neither exists yet.
 *
 * <p><strong>The login route is skipped.</strong> A client whose session expired may still attach
 * its stale token to the login call; refusing that call would lock the user out of the one route
 * that fixes the problem. Login ignores {@code Authorization} entirely.
 *
 * <p><strong>Deliberately not a Spring bean.</strong> Boot registers every {@code Filter} bean with
 * the servlet container as well, which would run this a second time outside the security chain.
 * {@code SecurityConfiguration} constructs it and places it in each chain instead.
 */
@Slf4j
class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Bearer";

    private static final RequestMatcher LOGIN =
            new AntPathRequestMatcher("/api/v1/auth/login", HttpMethod.POST.name());

    private final JwtTokenService tokens;
    private final AuthenticationEntryPoint entryPoint;

    JwtAuthenticationFilter(JwtTokenService tokens, AuthenticationEntryPoint entryPoint) {
        this.tokens = tokens;
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return LOGIN.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!isBearer(header)) {
            chain.doFilter(request, response);
            return;
        }

        AuthenticatedPrincipal principal;
        try {
            principal = tokens.verify(header.substring(SCHEME.length()).trim());
        } catch (InvalidTokenException refused) {
            SecurityContextHolder.clearContext();
            log.warn(
                    "Bearer token refused on {} {}: {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    refused.reason());
            entryPoint.commence(request, response, refused);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))));
        SecurityContextHolder.setContext(context);

        chain.doFilter(request, response);
    }

    /** RFC 7235: the scheme is case-insensitive. {@code "Bearer"} with no token counts, and fails. */
    private static boolean isBearer(String header) {
        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return false;
        }
        return header.length() == SCHEME.length() || Character.isWhitespace(header.charAt(SCHEME.length()));
    }
}
