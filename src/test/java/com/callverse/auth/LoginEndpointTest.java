package com.callverse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The login endpoint, exercised over HTTP against the real schema and the real seeded accounts.
 *
 * <p><strong>Why the seeded accounts and not fixtures.</strong> A login that works only for a user
 * this test created proves the code path and nothing about the demo. The four accounts in
 * {@code V2__seed_reference.sql} are what a jury will actually type, so they are what gets tested.
 *
 * <p><strong>Why {@code dev}.</strong> The two filter chains are profile-bound: {@code dev} permits
 * every request, everything else denies all but the health probe. Since no authentication filter
 * exists yet, {@code dev} is the only profile under which any endpoint is reachable. Making login
 * reachable under the deny-by-default chain is sub-phase 2.3, and this test will need revisiting
 * then — deliberately, because that change should not pass silently.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LoginEndpointTest extends AbstractPersistenceTest {

    /** Must match {@code AbstractPersistenceTest}'s registered {@code jwt.secret}. */
    private static final String TEST_SIGNING_KEY =
            "test-only-signing-key-not-used-for-anything-real-0123456789";

    private static final String DEV_PASSWORD = "CallVerse!Dev2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;

    static Stream<Arguments> seededAccounts() {
        return Stream.of(
                Arguments.of("customer@callverse.local", UserRole.CUSTOMER),
                Arguments.of("advisor@callverse.local", UserRole.ADVISOR),
                Arguments.of("supervisor@callverse.local", UserRole.SUPERVISOR),
                Arguments.of("admin@callverse.local", UserRole.ADMIN));
    }

    @ParameterizedTest(name = "{0} logs in and receives a token carrying role {1}")
    @MethodSource("seededAccounts")
    @DisplayName("each seeded account logs in and receives a signed token with its own role")
    void seededAccountLogsIn(String email, UserRole expectedRole) throws Exception {
        JsonNode body = postLogin(email, DEV_PASSWORD, 200);

        assertThat(body.get("role").asText()).isEqualTo(expectedRole.name());
        assertThat(body.get("expiresAt").asText()).isNotBlank();

        Claims claims =
                Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(TEST_SIGNING_KEY.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(body.get("token").asText())
                        .getPayload();

        assertThat(claims.get("email", String.class)).isEqualTo(email);
        assertThat(claims.get("role", String.class)).isEqualTo(expectedRole.name());
        assertThat(claims.getSubject())
                .as("the subject must be the app_user UUID, not the email")
                .matches("[0-9a-fA-F-]{36}");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    @DisplayName("a wrong password and an unknown email are indistinguishable over HTTP")
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        JsonNode wrongPassword = postLogin("advisor@callverse.local", "not-the-password", 401);
        JsonNode unknownEmail = postLogin("nobody@callverse.local", DEV_PASSWORD, 401);

        assertThat(wrongPassword.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownEmail.get("code").asText())
                .as("a differing code would let an attacker enumerate accounts")
                .isEqualTo(wrongPassword.get("code").asText());
        assertThat(unknownEmail.get("message").asText())
                .as("a differing message would let an attacker enumerate accounts")
                .isEqualTo(wrongPassword.get("message").asText());

        // the frozen five-field envelope, unchanged
        assertThat(wrongPassword.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path");
        assertThat(wrongPassword.get("status").asInt()).isEqualTo(401);
    }

    @Test
    @Transactional
    @DisplayName("a deactivated account cannot log in even with the correct password")
    void deactivatedAccountIsRefused() throws Exception {
        AppUser inactive = new AppUser();
        inactive.setEmail("deactivated.login@callverse.local");
        // the advisor hash from V2, which this same password is known to verify against
        inactive.setPasswordHash("$2a$10$O4PMyWbBGy1hg0rljmTGSuPZhArtP37.EpTqQtH.D4xufCnGFrdTe");
        inactive.setFirstName("Deactivated");
        inactive.setLastName("Account");
        inactive.setRole(UserRole.ADVISOR);
        inactive.setActive(false);
        em.persist(inactive);
        em.flush();

        JsonNode body = postLogin("deactivated.login@callverse.local", DEV_PASSWORD, 401);

        assertThat(body.get("code").asText())
                .as("V1__init.sql:45 - inactive users are never authenticated")
                .isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("a blank password is rejected as a validation failure, not as bad credentials")
    void blankPasswordIsAValidationFailure() throws Exception {
        JsonNode body = postLogin("advisor@callverse.local", "", 400);

        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    private JsonNode postLogin(String email, String password, int expectedStatus) throws Exception {
        MvcResult result =
                mockMvc
                        .perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper
                                                        .createObjectNode()
                                                        .put("email", email)
                                                        .put("password", password)
                                                        .toString()))
                        .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("status for %s -> body was: %s", email, result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
