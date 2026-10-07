package com.callverse.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata and the security schemes the API will use.
 *
 * <p>This lives in {@code infrastructure.config} rather than in {@code host.api} on purpose: it is
 * framework wiring, not delivery. The controllers stay free of it.
 *
 * <p><strong>Why the document matters more here than in a typical project.</strong> The Next.js
 * team generates its TypeScript client from {@code /v3/api-docs}, so this document is a published
 * contract rather than documentation. A renamed field here is a compile error there.
 *
 * <p><strong>One security scheme.</strong> {@code bearerAuth} is the user JWT for {@code /api/v1/**},
 * issued by the login endpoint and read by {@code JwtAuthenticationFilter}.
 *
 * <p>The {@code serviceKey} scheme for the AI service's {@code /internal/**} tool API was withdrawn
 * on 2026-09-30 together with that API, pending the AI-integration phase. The git tag
 * {@code internal-tools-http-surface} holds the last version of both. When it returns it must stay
 * a distinct scheme, not a variant of the JWT: the AI service is not a user and has no role.
 */
@Configuration
public class OpenApiConfiguration {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI callVerseOpenApi(
            @Value("${callverse.version}") String version,
            @Value("${server.port:8080}") int port) {

        return new OpenAPI()
                .info(new Info()
                        .title("CallVerse Backend API")
                        .version(version)
                        .description("""
                                Backend for CallVerse, a digital twin of a retail bank's \
                                customer relation center.

                                **Two modes, one API.** Every endpoint behaves identically in live \
                                mode (a real person on the customer portal) and simulation mode \
                                (synthetic customers driving a reinforcement-learning experiment). \
                                The only difference is the source of the customers, expressed in \
                                the data as `customer.is_simulated` and `conversation.run_id`.

                                **The backend is the authority on business rules.** A commercial \
                                credit above an advisor's ceiling is refused here, whoever asks.

                                **Conventions.** Routes are versioned under `/api/v1`. UUIDs are \
                                exposed publicly and sequence identifiers never are. All timestamps \
                                are ISO-8601 UTC. Every failure returns the same envelope — \
                                `timestamp`, `status`, `code`, `message`, `path` — and clients \
                                branch on `code`, never on `message`.""")
                        .contact(new Contact().name("CallVerse backend").email("backend@callverse.local"))
                        .license(new License().name("Academic project - 3iL / ESPRIT")))
                .servers(List.of(
                        new Server().url("http://localhost:" + port).description("Local development"),
                        new Server().url("/").description("Relative to the deployed host")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        User access token from `POST /api/v1/auth/login`. \
                                        Carries the account identifier and one role of \
                                        CUSTOMER, ADVISOR, SUPERVISOR or ADMIN.

                                        HS512, valid for one hour by default. Missing, \
                                        expired or invalid: 401 UNAUTHENTICATED.""")));
    }
}
