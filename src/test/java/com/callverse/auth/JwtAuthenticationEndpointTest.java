package com.callverse.auth;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * The JWT filter, exercised over HTTP under the {@code dev} chain.
 *
 * <p><strong>Why {@code dev} here.</strong> The dev chain permits every request, so any 401 in this
 * class can only have come from the filter refusing a token — not from an authorization rule. That
 * isolates the filter's own decision. The {@code !dev} chain's 401-versus-403 behaviour is asserted
 * separately in {@code SecurityErrorContractTest}.
 *
 * <p>A presented-but-invalid token is refused even though the route is open: silently downgrading
 * a bad credential to anonymous would hide an expired session from the client, and the frontend's
 * refresh logic fires on exactly that 401.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class JwtAuthenticationEndpointTest extends AbstractPersistenceTest {

    private static final String DEV_PASSWORD = "CallVerse!Dev2026";
    private static final String OPEN_ROUTE = "/api/v1/health/status";
    private static final String ME = "/api/v1/auth/me";

    private static final UUID USER_ID = UUID.fromString("0b7c1d2e-3f40-4a51-8b62-7c83d94ea5f6");
    private static final String EMAIL = "someone@callverse.test";

    /** What every refused credential must look like to the caller, whatever was wrong with it. */
    private static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("a token from the real login endpoint identifies that account at /me")
    void loginTokenReachesAControllerWithThePrincipal() throws Exception {
        String token = login("advisor@callverse.local");
        String subject =
                Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(token)
                        .getPayload()
                        .getSubject();

        JsonNode me = call(get(ME).with(bearer(token)), 200);

        assertThat(me.get("userId").asText()).isEqualTo(subject);
        assertThat(me.get("email").asText()).isEqualTo("advisor@callverse.local");
        assertThat(me.get("role").asText()).isEqualTo("ADVISOR");
    }

    @Test
    @DisplayName("the principal comes from the claims alone: an account absent from the database still resolves")
    void principalIsBuiltFromClaimsWithoutADatabaseLookup() throws Exception {
        JsonNode me =
                call(get(ME).with(bearer(AuthenticatedRequests.validToken(USER_ID, EMAIL, UserRole.SUPERVISOR))), 200);

        assertThat(me.get("userId").asText()).isEqualTo(USER_ID.toString());
        assertThat(me.get("email").asText()).isEqualTo(EMAIL);
        assertThat(me.get("role").asText()).isEqualTo("SUPERVISOR");
    }

    static Stream<Arguments> refusedTokens() {
        return Stream.of(
                Arguments.of("expired", (Supplier<String>) () -> AuthenticatedRequests.expiredToken(USER_ID, EMAIL, UserRole.CUSTOMER)),
                Arguments.of("tampered payload", (Supplier<String>) () -> AuthenticatedRequests.tamperedToken(USER_ID, EMAIL, UserRole.CUSTOMER, UserRole.ADMIN)),
                Arguments.of("wrong key", (Supplier<String>) () -> AuthenticatedRequests.tokenSignedWithWrongKey(USER_ID, EMAIL, UserRole.CUSTOMER)),
                Arguments.of("HS256", (Supplier<String>) () -> AuthenticatedRequests.tokenSignedWithHs256(USER_ID, EMAIL, UserRole.CUSTOMER)),
                Arguments.of("alg none", (Supplier<String>) () -> AuthenticatedRequests.unsignedToken(USER_ID, EMAIL, UserRole.CUSTOMER)),
                Arguments.of("garbage", (Supplier<String>) () -> AuthenticatedRequests.GARBAGE),
                Arguments.of("empty", (Supplier<String>) () -> ""));
    }

    @ParameterizedTest(name = "{0} token -> the one indistinguishable 401")
    @MethodSource("refusedTokens")
    @DisplayName("every refused token gets the same status, code, message and challenge, even on an open route")
    void refusedTokensAreIndistinguishable(String mode, Supplier<String> token) throws Exception {
        MvcResult reference = perform(get(ME));
        MvcResult refused = perform(get(OPEN_ROUTE).with(bearer(token.get())));

        assertThat(refused.getResponse().getStatus()).as(mode).isEqualTo(401);
        JsonNode body = objectMapper.readTree(refused.getResponse().getContentAsString());
        JsonNode anonymous = objectMapper.readTree(reference.getResponse().getContentAsString());

        assertThat(body.fieldNames())
                .toIterable()
                .containsExactly("timestamp", "status", "code", "message", "path");
        assertThat(body.get("code").asText()).isEqualTo(UNAUTHENTICATED);
        assertThat(body.get("message").asText())
                .as("the message must not say which check failed")
                .isEqualTo(anonymous.get("message").asText());
        assertThat(refused.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("no token at /me is a 401 with the envelope, not an empty principal")
    void anonymousCallerAtMeIsUnauthenticated() throws Exception {
        JsonNode body = call(get(ME), 401);

        assertThat(body.get("code").asText()).isEqualTo(UNAUTHENTICATED);
        assertThat(body.fieldNames())
                .toIterable()
                .containsExactly("timestamp", "status", "code", "message", "path");
    }

    @Test
    @DisplayName("the Bearer scheme is matched case-insensitively (RFC 7235)")
    void lowerCaseSchemeIsAccepted() throws Exception {
        String token = AuthenticatedRequests.validToken(USER_ID, EMAIL, UserRole.CUSTOMER);

        call(get(ME).header(HttpHeaders.AUTHORIZATION, "bearer " + token), 200);
    }

    @Test
    @DisplayName("a non-Bearer scheme is not a token: the filter ignores it and the chain decides")
    void otherSchemesAreIgnored() throws Exception {
        call(get(OPEN_ROUTE).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"), 200);
    }

    @Test
    @DisplayName("the dev chain still admits anonymous callers: the filter authenticates, it does not authorize")
    void devChainStaysPermissive() throws Exception {
        call(get(OPEN_ROUTE), 200);
    }

    @Test
    @DisplayName("a stale token attached to a login request does not stop the user logging back in")
    void expiredTokenDoesNotBlockLogin() throws Exception {
        String stale = AuthenticatedRequests.expiredToken(USER_ID, EMAIL, UserRole.CUSTOMER);

        call(loginRequest("advisor@callverse.local").with(bearer(stale)), 200);
    }

    private String login(String email) throws Exception {
        return call(loginRequest(email), 200).get("token").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String email) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("email", email).put("password", DEV_PASSWORD).toString());
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode call(RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = perform(request);
        assertThat(result.getResponse().getStatus())
                .as("body was: %s", result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
