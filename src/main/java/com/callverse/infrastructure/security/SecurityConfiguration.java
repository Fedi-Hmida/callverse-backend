package com.callverse.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security wiring for the CallVerse backend: two profile-bound filter chains that share everything
 * except their authorization rules.
 *
 * <p><strong>What both chains share</strong>, applied by {@link #common} so the two cannot drift:
 *
 * <ul>
 *   <li>CORS from {@link CorsPolicyConfiguration}. Spring's {@code CorsFilter} runs ahead of
 *       authorization, so a preflight from an allowed origin succeeds even on a route that
 *       requires a token — a preflight never carries one.
 *   <li>{@link JwtAuthenticationFilter}, before {@code UsernamePasswordAuthenticationFilter}: a
 *       verified bearer token becomes the principal; a refused one is a 401.
 *   <li>An entry point and an access-denied handler that write the standard error envelope — 401
 *       {@code UNAUTHENTICATED} for an unidentified caller, 403 {@code ACCESS_DENIED} for an
 *       identified one who is not allowed. Without them Spring answers 403 with an empty body for
 *       both.
 *   <li>Stateless sessions and CSRF disabled. <strong>CSRF is off only because no cookie exists
 *       anywhere in the system</strong>: the token travels in the {@code Authorization} header,
 *       which a cross-site form cannot set. If a refresh token ever lands in a cookie (sub-phase
 *       2.4, Decision 2), this justification is void and CSRF must be revisited in both chains.
 * </ul>
 *
 * <p><strong>Still to come, and where.</strong> Role rules on individual endpoints are
 * {@code @PreAuthorize} in sub-phase 2.5 (blocked on schema change S-1). STOMP frames are
 * authenticated in 2.8. These two chains are the only ones: the AI service's {@code /internal/**}
 * chain and its service-key scheme were withdrawn on 2026-09-30 pending the AI-integration phase
 * (git tag {@code internal-tools-http-surface}), so {@code /internal/**} now falls to whichever of
 * these is active. No {@code UserDetailsService} is planned: the token's claims are the
 * principal, so there is nothing for one to load.
 */
@Configuration
@EnableWebSecurity
// Registers the interceptor that makes @PreAuthorize execute. Without it those
// annotations are inert metadata: they compile, they pass review, and they enforce
// nothing, with no warning and no failing test. GlobalExceptionHandler maps the
// AccessDeniedException it throws to 401/403 rather than letting the catch-all make it a 500.
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * Development chain: every request is permitted.
     *
     * <p>Bound to the {@code dev} profile so it cannot be switched on by accident elsewhere. The JWT
     * filter still runs, so a request carrying a valid token is identified and a request carrying a
     * refused one is a 401 — but no route requires a token. The filter authenticates; it does not
     * authorize.
     */
    @Bean
    @Order(2) // slot 1 stays free for the /internal chain the AI-integration phase restores
    @Profile("dev")
    SecurityFilterChain developmentFilterChain(
            HttpSecurity http,
            JwtTokenService tokens,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return common(http, tokens, entryPoint, accessDeniedHandler)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Default chain for every profile other than {@code dev}: deny by default.
     *
     * <p>Three routes are open, each for a reason that does not depend on 2.5's role rules:
     *
     * <ul>
     *   <li>{@code /actuator/health/**} — the platform decides from it whether the instance lives.
     *   <li>{@code POST /api/v1/auth/login} — the only way to obtain a token; closing it makes every
     *       other route unreachable.
     *   <li>{@code GET /api/v1/auth/me} — any authenticated caller, since it only echoes back the
     *       caller's own token.
     * </ul>
     *
     * <p>Everything else is {@code denyAll()}, which refuses even a valid token: anonymous callers
     * get 401, authenticated ones 403. Opening further routes is 2.5's work, route by route.
     *
     * <p>Boot's generated security password is still logged at startup:
     * {@code UserDetailsServiceAutoConfiguration} backs off on a {@code UserDetailsService},
     * {@code AuthenticationProvider} or {@code AuthenticationManager} bean, and this application
     * declares none because the token is the principal. The generated in-memory user is reachable
     * by no mechanism here — neither chain enables form login or HTTP Basic.
     */
    @Bean
    @Order(2) // slot 1 stays free for the /internal chain the AI-integration phase restores
    @Profile("!dev")
    SecurityFilterChain defaultFilterChain(
            HttpSecurity http,
            JwtTokenService tokens,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return common(http, tokens, entryPoint, accessDeniedHandler)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        // Staff-only reads. The chain grants reachability to any
                        // authenticated caller; @PreAuthorize on the controller decides the
                        // role. Both layers are required - dropping this line makes the
                        // route unreachable outside dev, and dropping the annotation makes
                        // it readable by every authenticated caller.
                        .requestMatchers(HttpMethod.GET, "/api/v1/customers/**").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    private static HttpSecurity common(
            HttpSecurity http,
            JwtTokenService tokens,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokens, entryPoint),
                        UsernamePasswordAuthenticationFilter.class);
    }
}
