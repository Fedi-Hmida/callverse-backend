package com.callverse.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security wiring for the CallVerse backend.
 *
 * <p><strong>TODO — this class contains no authentication yet.</strong> Issuing and validating JWTs
 * is a separate task. What exists here is the filter chain skeleton and the session policy, so that
 * the JWT work has a defined place to land and so that the walking-skeleton endpoint is reachable
 * in the meantime. Specifically, still to be built:
 *
 * <ul>
 *   <li>a {@code JwtAuthenticationFilter} placed before
 *       {@code UsernamePasswordAuthenticationFilter}, reading the {@code jwt.secret} property;
 *   <li>a {@code UserDetailsService} backed by the advisor and customer tables;
 *   <li>role-based rules for CUSTOMER, ADVISOR, SUPERVISOR and ADMIN;
 *   <li>authentication on {@code /internal/**}, which the Python AI service calls and which must
 *       never be reachable from the public internet;
 *   <li>CORS configuration for the Angular origin.
 * </ul>
 *
 * <p>Sessions are stateless in both chains below and should stay that way: the Angular frontend and
 * the Python service are separate origins, so a server-side session is the wrong mechanism.
 */
@Configuration
@EnableWebSecurity
// Registers the interceptor that makes @PreAuthorize execute. Without it those
// annotations are inert metadata: they compile, they pass review, and they enforce
// nothing, with no warning and no failing test. Added while zero @PreAuthorize exist
// so it is a no-op today and a working guard the moment the first one is written.
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * Development chain: everything is permitted.
     *
     * <p>This exists so that the API, Swagger UI and the health endpoint can be exercised before
     * authentication is implemented. It is bound to the {@code dev} profile precisely so that it
     * cannot be switched on by accident anywhere else.
     */
    @Bean
    @Profile("dev")
    SecurityFilterChain developmentFilterChain(
            HttpSecurity http, JwtTokenService tokens, AuthenticationEntryPoint entryPoint)
            throws Exception {
        return http
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokens, entryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                // No cookies are used, so there is no CSRF vector to protect; leaving CSRF on
                // would only reject the frontend's POSTs for no gain.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * Default chain for every profile other than {@code dev}: deny by default.
     *
     * <p>This is deliberately not an implementation of authentication. It is the absence of one,
     * made explicit: a deployment that forgets to set a profile gets a uniform denial rather than
     * Boot's auto-configured chain. Only the health probe is open, because the platform needs it
     * to decide whether the instance is alive.
     *
     * <p>Two corrections to an earlier version of this comment, both established by running the
     * application rather than by reading it. First, Boot's generated security password IS still
     * logged on every boot today: UserDetailsServiceAutoConfiguration backs off on an
     * AuthenticationManager, AuthenticationProvider or UserDetailsService bean, not on a
     * SecurityFilterChain, and this class declares none of those. Declaring a UserDetailsService
     * in Phase 2 will silence it. Second, this chain returns 403, not 401: with no
     * AuthenticationEntryPoint registered, Spring Security falls back to
     * Http403ForbiddenEntryPoint.
     */
    @Bean
    @Profile("!dev")
    SecurityFilterChain defaultFilterChain(
            HttpSecurity http, JwtTokenService tokens, AuthenticationEntryPoint entryPoint)
            throws Exception {
        return http
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokens, entryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
