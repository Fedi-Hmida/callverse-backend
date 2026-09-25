package com.callverse.infrastructure.security;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Which browser origins may call this API directly.
 *
 * <p><strong>Built for Decision 3, Option D — the browser calls this API itself</strong>, which is
 * the recommendation on record in {@code docs/frontend/NEXTJS_PIVOT_AUDIT.md}. The decision is not
 * confirmed. Under Option B (a Next.js backend-for-frontend) REST would need no CORS at all, and a
 * session cookie would appear — which voids the CSRF reasoning in {@code SecurityConfiguration}.
 *
 * <p><strong>The origin is configuration, never code.</strong> Which origin the frontend is served
 * from is knowledge only the frontend repository has. {@code callverse.cors.allowed-origins} takes a
 * comma-separated list and defaults to {@code http://localhost:3000}, the Next.js dev server.
 * Exact origins only: no wildcard and no pattern.
 *
 * <p><strong>Credentials are never allowed.</strong> The token travels in the
 * {@code Authorization} header, which needs no {@code allowCredentials}; that flag exists for
 * cookies, and a permissive origin combined with it is the classic misconfiguration that lets any
 * site make authenticated requests on a user's behalf.
 *
 * <p>Methods are the REST verbs the API serves or will serve through 2.5; headers are only the two
 * the frontend sends. A preflight from an unlisted origin, or asking for anything else, is answered
 * 403 by Spring's {@code CorsFilter} with a plain-text body rather than the error envelope — browsers
 * never expose a preflight body to scripts, so the envelope would be unreadable there anyway.
 *
 * <p>Named {@code CorsPolicyConfiguration} rather than {@code CorsConfiguration} because the latter
 * is the Spring type this class builds.
 */
@Configuration
public class CorsPolicyConfiguration {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${callverse.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration policy = new CorsConfiguration();
        policy.setAllowedOrigins(allowedOrigins);
        policy.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        policy.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        policy.setAllowCredentials(false);
        policy.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", policy);
        return source;
    }
}
