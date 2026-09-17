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
 * <p><strong>Why the document matters more here than in a typical project.</strong> The Angular
 * team generates its TypeScript client from {@code /v3/api-docs}, so this document is a published
 * contract rather than documentation. A renamed field here is a compile error there.
 *
 * <p><strong>The two security schemes are declared but not yet enforced.</strong> No filter reads
 * them and no endpoint requires them — authentication arrives in Phase 2. They are declared now for
 * one practical reason: without a registered scheme, Swagger UI renders no "Authorize" button, and
 * the moment the first protected endpoint exists it becomes untestable from the browser. Declaring
 * them costs nothing today and removes a stumbling block later.
 *
 * <ul>
 *   <li>{@code bearerAuth} — the user JWT, for {@code /api/v1/**}. Issued by the login endpoint.
 *   <li>{@code serviceKey} — a header-based key for {@code /internal/**}, which the Python AI
 *       service calls. <strong>A distinct scheme, not a variant of the JWT.</strong> Conflating the
 *       two is the mistake this separation exists to prevent: the AI service is not a user, has no
 *       role, and its access is bounded by business rules such as the commercial-credit ceiling.
 * </ul>
 */
@Configuration
public class OpenApiConfiguration {

    private static final String BEARER_SCHEME = "bearerAuth";
    private static final String SERVICE_KEY_SCHEME = "serviceKey";

    @Bean
    OpenAPI callVerseOpenApi(
            @Value("${callverse.version}") String version,
            @Value("${server.port:8080}") int port) {

        return new OpenAPI()
                .info(new Info()
                        .title("CallVerse Backend API")
                        .version(version)
                        .description("""
                                Backend for CallVerse, a digital twin of a telecom customer \
                                relation center.

                                **Two modes, one API.** Every endpoint behaves identically in live \
                                mode (a real person on the customer portal) and simulation mode \
                                (synthetic customers driving a reinforcement-learning experiment). \
                                The only difference is the source of the customers, expressed in \
                                the data as `customer.is_simulated` and `conversation.run_id`.

                                **The backend is the authority on business rules.** The autonomous \
                                agents call `/internal` to act; they never decide. A commercial \
                                credit above an advisor's ceiling is refused here, whatever the \
                                agent believes.

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

                                        Not yet implemented - Phase 2."""))
                        .addSecuritySchemes(SERVICE_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Key")
                                .description("""
                                        Shared service key for `/internal/**`, used by the Python \
                                        AI service. Not a user credential: it carries no role and \
                                        no identity, and its access is bounded by business rules \
                                        rather than by authorization.

                                        Not yet implemented - Phase 3.""")));
    }
}
