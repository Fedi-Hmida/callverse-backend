package com.callverse.auth;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * The deny-by-default chain, which is where 401 versus 403 actually matters.
 *
 * <p><strong>The first test in the repository to run under a profile other than {@code dev}.</strong>
 * {@code prod} is chosen because it is what {@code docker-compose.yml} defaults to; any non-dev
 * profile selects the same chain. Every route except health, login and {@code /me} is
 * {@code denyAll()} here, so a single route separates the two outcomes cleanly: anonymous is 401
 * (who are you?), authenticated is 403 (you may not). The frontend refreshes on 401 and must not on
 * 403, so the two being swapped presents as a user stuck in a broken session.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class SecurityErrorContractTest extends AbstractPersistenceTest {

    private static final String DENIED_ROUTE = "/api/v1/health/status";
    private static final String DEV_PASSWORD = "CallVerse!Dev2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private final String customerToken =
            AuthenticatedRequests.validToken(UUID.randomUUID(), "customer@callverse.test", UserRole.CUSTOMER);

    @Test
    @DisplayName("no token on a closed route: 401 UNAUTHENTICATED in the envelope, with a Bearer challenge")
    void anonymousIsUnauthenticated() throws Exception {
        MvcResult result = perform(get(DENIED_ROUTE));

        ErrorEnvelope.assertConforms(body(result), 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("a valid token on a closed route: 403 ACCESS_DENIED in the envelope, never 401")
    void authenticatedButForbiddenIsAccessDenied() throws Exception {
        MvcResult result = perform(get(DENIED_ROUTE).with(bearer(customerToken)));

        ErrorEnvelope.assertConforms(body(result), 403, "ACCESS_DENIED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .as("a 403 must not invite the client to re-authenticate")
                .isNull();
    }

    @Test
    @DisplayName("a refused token and no token at all are indistinguishable to the caller")
    void refusedTokenLooksLikeNoToken() throws Exception {
        JsonNode none = body(perform(get(DENIED_ROUTE)));
        JsonNode expired =
                body(perform(get(DENIED_ROUTE).with(bearer(
                        AuthenticatedRequests.expiredToken(UUID.randomUUID(), "x@callverse.test", UserRole.ADMIN)))));

        assertThat(expired.get("status")).isEqualTo(none.get("status"));
        assertThat(expired.get("code")).isEqualTo(none.get("code"));
        assertThat(expired.get("message")).isEqualTo(none.get("message"));
    }

    @Test
    @DisplayName("the chain's 401 and 403 have exactly the shape GlobalExceptionHandler writes")
    void chainAndMvcWritersProduceTheSameShape() throws Exception {
        MvcResult chain401 = perform(get(DENIED_ROUTE));
        MvcResult chain403 = perform(get(DENIED_ROUTE).with(bearer(customerToken)));
        MvcResult mvc401 = perform(login("advisor@callverse.local", "not-the-password"));

        ErrorEnvelope.assertConforms(body(mvc401), 401, "INVALID_CREDENTIALS");
        assertThat(ErrorEnvelope.shapeOf(body(chain401))).isEqualTo(ErrorEnvelope.shapeOf(body(mvc401)));
        assertThat(ErrorEnvelope.shapeOf(body(chain403))).isEqualTo(ErrorEnvelope.shapeOf(body(mvc401)));
        assertThat(chain401.getResponse().getContentType()).isEqualTo(mvc401.getResponse().getContentType());
        assertThat(chain403.getResponse().getContentType()).isEqualTo(mvc401.getResponse().getContentType());
    }

    @Test
    @DisplayName("login is reachable under the deny-by-default chain")
    void loginIsReachable() throws Exception {
        MvcResult result = perform(login("customer@callverse.local", DEV_PASSWORD));

        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("/me admits a valid token and refuses its absence with 401")
    void meRequiresAuthentication() throws Exception {
        assertThat(perform(get("/api/v1/auth/me").with(bearer(customerToken))).getResponse().getStatus())
                .isEqualTo(200);
        ErrorEnvelope.assertConforms(body(perform(get("/api/v1/auth/me"))), 401, "UNAUTHENTICATED");
    }

    private RequestBuilder login(String email, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("email", email).put("password", password).toString());
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content)
                .as("status %d returned an empty body", result.getResponse().getStatus())
                .isNotBlank();
        return objectMapper.readTree(content);
    }
}
