package com.callverse.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

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
    SecurityFilterChain developmentFilterChain(HttpSecurity http) throws Exception {
        return http
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
     * made explicit. Without this bean, a deployment that simply forgets to set the profile would
     * fall back to Boot's auto-configured chain and come up with a generated password printed to
     * the log, which is a far worse failure mode than a uniform 401. Only the health probe is open,
     * because the platform needs it to decide whether the instance is alive.
     */
    @Bean
    @Profile("!dev")
    SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
