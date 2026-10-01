package com.callverse.auth;

import static com.callverse.persistence.AbstractPersistenceTest.TEST_JWT_SECRET;

import com.callverse.core.domain.enums.UserRole;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Mints access tokens for tests and attaches them to MockMvc requests.
 *
 * <p><strong>One helper, on purpose.</strong> Every sub-phase from 2.5 onward needs an
 * authenticated request, and there is no {@code @WithMockUser} here: a mock user skips the filter,
 * so it would prove nothing about the one component that turns a header into a principal. These
 * tokens go through the real filter and the real verification. Improvising a helper per test is
 * how five subtly different claim layouts get written, so extend this class instead.
 *
 * <p>The valid tokens use {@link com.callverse.persistence.AbstractPersistenceTest#TEST_JWT_SECRET},
 * the key every Spring test context verifies with, and the claim layout the issuer writes:
 * {@code sub} = the account UUID, {@code email}, {@code role}, {@code iat}, {@code exp}. The
 * deliberately broken variants each break exactly one thing, so a test that expects a rejection
 * learns which property was actually checked.
 *
 * <p>Times come from the wall clock. That is correct here: the application under test runs on
 * {@code Clock.systemUTC()}, and these tokens must agree with it.
 */
public final class AuthenticatedRequests {

    /** Three dot-separated segments that are not base64url JSON. */
    public static final String GARBAGE = "not.a.jwt";

    private static final SecretKey KEY =
            Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));

    private static final SecretKey OTHER_KEY =
            Keys.hmacShaKeyFor(
                    "a-different-key-of-the-right-length-that-the-application-never-sees-0123"
                            .getBytes(StandardCharsets.UTF_8));

    private static final Duration LIFETIME = Duration.ofHours(1);

    private AuthenticatedRequests() {}

    /** Adds {@code Authorization: Bearer <token>} to a MockMvc request. */
    public static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    /** A token the application must accept. */
    public static String validToken(UUID userId, String email, UserRole role) {
        Instant now = Instant.now();
        return claims(userId, email, role, now, now.plus(LIFETIME)).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    /** Correctly signed, but its {@code exp} passed an hour ago. */
    /** A valid token that expires after {@code lifetime}: for sessions that outlive their token. */
    public static String tokenExpiringIn(Duration lifetime, UUID userId, String email, UserRole role) {
        Instant now = Instant.now();
        return claims(userId, email, role, now, now.plus(lifetime)).signWith(KEY, Jwts.SIG.HS512).compact();
    }

    public static String expiredToken(UUID userId, String email, UserRole role) {
        Instant issued = Instant.now().minus(LIFETIME.multipliedBy(2));
        return claims(userId, email, role, issued, issued.plus(LIFETIME))
                .signWith(KEY, Jwts.SIG.HS512)
                .compact();
    }

    /** Well-formed HS512, signed with a key the application does not hold. */
    public static String tokenSignedWithWrongKey(UUID userId, String email, UserRole role) {
        Instant now = Instant.now();
        return claims(userId, email, role, now, now.plus(LIFETIME))
                .signWith(OTHER_KEY, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * Signed with the application's own secret, but as HS256. Verifies cryptographically if the
     * parser trusts the header's {@code alg}, which is exactly what pinning the algorithm prevents.
     */
    public static String tokenSignedWithHs256(UUID userId, String email, UserRole role) {
        Instant now = Instant.now();
        SecretKey sameSecretAsHs256 =
                new SecretKeySpec(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return claims(userId, email, role, now, now.plus(LIFETIME))
                .signWith(sameSecretAsHs256, Jwts.SIG.HS256)
                .compact();
    }

    /** {@code alg: none}: the classic downgrade, with an empty signature segment. */
    public static String unsignedToken(UUID userId, String email, UserRole role) {
        Instant now = Instant.now();
        return claims(userId, email, role, now, now.plus(LIFETIME)).compact();
    }

    /**
     * A valid token whose payload was edited to claim {@code forgedRole}, with the original
     * signature left in place — the privilege-escalation attempt a signature exists to stop.
     */
    public static String tamperedToken(UUID userId, String email, UserRole realRole, UserRole forgedRole) {
        String[] parts = validToken(userId, email, realRole).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forged =
                payload.replace("\"role\":\"" + realRole.name() + "\"", "\"role\":\"" + forgedRole.name() + "\"");
        if (forged.equals(payload)) {
            throw new IllegalStateException("role claim not found in payload: " + payload);
        }
        String encoded =
                Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + encoded + "." + parts[2];
    }

    private static JwtBuilder claims(
            UUID userId, String email, UserRole role, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt));
    }
}
