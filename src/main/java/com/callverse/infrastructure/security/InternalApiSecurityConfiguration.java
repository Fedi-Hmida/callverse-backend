package com.callverse.infrastructure.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The filter chain for {@code /internal/**}, the tool API the Python AI service calls.
 *
 * <p><strong>Not bound to a profile, and ordered first.</strong> The user chains in
 * {@code SecurityConfiguration} are profile-bound, and the {@code dev} one permits every request.
 * If {@code /internal} fell through to it, the tools — including, later, the one that grants money —
 * would be open under {@code dev} and nothing would look broken. This chain claims
 * {@code /internal/**} before either user chain is consulted, in every profile, so the key is
 * required everywhere. {@code @Order} is explicit on all three chains: without it, their relative
 * order is not defined.
 *
 * <p><strong>What is deliberately absent.</strong> No JWT filter: a user token must open nothing
 * here. No CORS: this is server-to-server, and a browser has no business calling it. CSRF is off for
 * the reason given in {@code SecurityConfiguration} — no cookie exists — and sessions are stateless.
 *
 * <p>The entry point is the service-key variant, which writes the same envelope as the user chains
 * but no {@code WWW-Authenticate: Bearer}: that scheme is not accepted here.
 */
@Configuration
public class InternalApiSecurityConfiguration {

    static final String INTERNAL_PATHS = "/internal/**";

    @Bean
    @Order(1)
    SecurityFilterChain internalApiFilterChain(
            HttpSecurity http,
            ServiceKeyVerifier verifier,
            @Qualifier("serviceKeyAuthenticationEntryPoint") AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http
                .securityMatcher(INTERNAL_PATHS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(
                        new ServiceKeyAuthenticationFilter(verifier, entryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().hasAuthority(ServiceKeyAuthenticationFilter.AUTHORITY))
                .build();
    }
}
