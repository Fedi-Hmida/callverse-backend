package com.callverse.infrastructure.security;

import static com.callverse.persistence.AbstractPersistenceTest.TEST_JWT_SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.auth.AuthenticatedRequests;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.IssuedToken;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.infrastructure.security.InvalidTokenException.Reason;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Token verification in isolation: no Spring context, no container. Verification is a pure function
 * from a string and a clock to a principal or a refusal, so it is tested as one.
 *
 * <p>Each rejection test breaks exactly one property of an otherwise valid token and asserts the
 * {@link Reason}. The reason exists only for the server log — the caller receives the same 401
 * whatever it is, which {@code JwtAuthenticationEndpointTest} asserts over HTTP.
 */
class JwtTokenServiceTest {

    private static final long ONE_HOUR_MS = Duration.ofHours(1).toMillis();
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String EMAIL = "advisor@callverse.local";

    private final JwtTokenService service =
            new JwtTokenService(TEST_JWT_SECRET, ONE_HOUR_MS, Clock.systemUTC());

    @Test
    @DisplayName("a token the service issued verifies back to the same account, email and role")
    void issuedTokenRoundTrips() {
        IssuedToken token = service.issue(USER_ID, EMAIL, UserRole.ADVISOR);

        AuthenticatedPrincipal principal = service.verify(token.value());

        assertThat(principal).isEqualTo(new AuthenticatedPrincipal(USER_ID, EMAIL, UserRole.ADVISOR));
    }

    @Test
    @DisplayName("the issuer signs with HS512 explicitly, whatever the key length would imply")
    void issuerSignsWithHs512() {
        String header = decodeSegment(service.issue(USER_ID, EMAIL, UserRole.ADVISOR).value(), 0);

        assertThat(header).contains("\"alg\":\"HS512\"");
    }

    @Test
    @DisplayName("a secret shorter than 512 bits fails at construction, not at the first login")
    void keyTooShortForHs512FailsAtStartup() {
        // 48 bytes: enough for HS384, which jjwt would otherwise pick silently.
        String secret384 = "a-48-byte-secret-long-enough-for-hs384-only-xyz0";

        assertThatThrownBy(() -> new JwtTokenService(secret384, ONE_HOUR_MS, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("512 bits");
    }

    @Test
    @DisplayName("a token past its exp is refused as EXPIRED")
    void expiredTokenIsRefused() {
        assertRefused(AuthenticatedRequests.expiredToken(USER_ID, EMAIL, UserRole.ADVISOR), Reason.EXPIRED);
    }

    @Test
    @DisplayName("expiry is judged by the injected clock, not the machine's")
    void expiryUsesTheInjectedClock() {
        // Far in the future, so the wall clock considers this token not yet expired at all.
        Instant issuedAt = Instant.parse("2099-01-01T00:00:00Z");
        String token =
                new JwtTokenService(TEST_JWT_SECRET, ONE_HOUR_MS, Clock.fixed(issuedAt, ZoneOffset.UTC))
                        .issue(USER_ID, EMAIL, UserRole.ADVISOR)
                        .value();

        JwtTokenService twoHoursLater =
                new JwtTokenService(
                        TEST_JWT_SECRET,
                        ONE_HOUR_MS,
                        Clock.fixed(issuedAt.plus(Duration.ofHours(2)), ZoneOffset.UTC));

        assertThatThrownBy(() -> twoHoursLater.verify(token))
                .isInstanceOfSatisfying(
                        InvalidTokenException.class, e -> assertThat(e.reason()).isEqualTo(Reason.EXPIRED));
    }

    @Test
    @DisplayName("a payload edited to claim ADMIN, original signature kept, is refused as BAD_SIGNATURE")
    void tamperedPayloadIsRefused() {
        assertRefused(
                AuthenticatedRequests.tamperedToken(USER_ID, EMAIL, UserRole.CUSTOMER, UserRole.ADMIN),
                Reason.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("a token signed with another key is refused as BAD_SIGNATURE")
    void wrongKeyIsRefused() {
        assertRefused(
                AuthenticatedRequests.tokenSignedWithWrongKey(USER_ID, EMAIL, UserRole.ADVISOR),
                Reason.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("HS256 signed with the real secret is refused: the algorithm is pinned, not negotiated")
    void hs256IsRefused() {
        assertRefused(
                AuthenticatedRequests.tokenSignedWithHs256(USER_ID, EMAIL, UserRole.ADVISOR),
                Reason.UNSUPPORTED_ALGORITHM);
    }

    @Test
    @DisplayName("alg: none is refused")
    void unsignedTokenIsRefused() {
        assertRefused(
                AuthenticatedRequests.unsignedToken(USER_ID, EMAIL, UserRole.ADVISOR),
                Reason.UNSUPPORTED_ALGORITHM);
    }

    @Test
    @DisplayName("a string that is not a JWT is refused as MALFORMED")
    void garbageIsRefused() {
        assertRefused(AuthenticatedRequests.GARBAGE, Reason.MALFORMED);
    }

    @Test
    @DisplayName("a correctly signed token whose role is not a known role is refused as INVALID_CLAIMS")
    void unknownRoleIsRefused() {
        String token =
                Jwts.builder()
                        .subject(USER_ID.toString())
                        .claim("email", EMAIL)
                        .claim("role", "ROOT")
                        .issuedAt(new Date())
                        .expiration(new Date(System.currentTimeMillis() + ONE_HOUR_MS))
                        .signWith(
                                Keys.hmacShaKeyFor(
                                        TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)),
                                Jwts.SIG.HS512)
                        .compact();

        assertRefused(token, Reason.INVALID_CLAIMS);
    }

    @Test
    @DisplayName("a correctly signed token with no exp is refused: a token that never expires is not issued here")
    void missingExpiryIsRefused() {
        String token =
                Jwts.builder()
                        .subject(USER_ID.toString())
                        .claim("email", EMAIL)
                        .claim("role", UserRole.ADVISOR.name())
                        .signWith(
                                Keys.hmacShaKeyFor(
                                        TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)),
                                Jwts.SIG.HS512)
                        .compact();

        assertRefused(token, Reason.INVALID_CLAIMS);
    }

    private void assertRefused(String token, Reason expected) {
        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOfSatisfying(
                        InvalidTokenException.class, e -> assertThat(e.reason()).isEqualTo(expected));
    }

    private static String decodeSegment(String token, int index) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[index]), StandardCharsets.UTF_8);
    }
}
