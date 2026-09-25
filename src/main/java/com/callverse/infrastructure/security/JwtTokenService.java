package com.callverse.infrastructure.security;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.IssuedToken;
import com.callverse.core.application.interfaces.TokenIssuer;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.infrastructure.security.InvalidTokenException.Reason;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies access tokens with HMAC-SHA512 (HS512).
 *
 * <p><strong>The algorithm is pinned, on both sides.</strong> Left to itself, jjwt picks the HMAC
 * variant from the key's length: a 59-byte secret silently yields HS384, a 32-byte one HS256. And a
 * verifier that trusts the token's own {@code alg} header will accept HS256 signed with the same
 * secret, or refuse nothing at all for {@code alg: none} if unsecured tokens are enabled. So the
 * issuer names HS512 explicitly and the parser is restricted to HS512 alone; any other header is a
 * refusal before a signature is even computed.
 *
 * <p><strong>Three startup guards, in order.</strong> {@code jwt.secret: ${JWT_SECRET}} has no
 * default, so a missing secret fails placeholder resolution. {@link Keys#hmacShaKeyFor} rejects
 * anything under 256 bits. And this constructor rejects anything under 512 bits, the minimum for
 * HS512 — without it, a 48-byte secret would start the application and then fail on the first
 * login. All three fail while constructing this bean, which is the earliest moment they can.
 *
 * <p><strong>What the token carries, and what it must never carry.</strong> Subject (the
 * {@code app_user.id}), email and role — nothing else. In particular not the password hash, and not
 * any field that the client could mistake for authorisation state. The server re-reads the role from
 * the verified token on every request; the copy returned alongside the token is a convenience for
 * choosing which UI to render, never a trusted input.
 *
 * <p><strong>Time is the injected {@link Clock}, for both issuing and expiry.</strong> jjwt would
 * otherwise read the system clock for {@code exp}, which a test cannot move.
 */
@Component
public class JwtTokenService implements TokenIssuer {

    private static final int MIN_KEY_BITS = 512;
    private static final String EMAIL_CLAIM = "email";
    private static final String ROLE_CLAIM = "role";

    private final SecretKey signingKey;
    private final long expirationMillis;
    private final Clock clock;
    private final JwtParser parser;

    JwtTokenService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMillis,
            Clock clock) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length * 8 < MIN_KEY_BITS) {
            throw new IllegalStateException(
                    "jwt.secret must be at least %d bits (64 bytes) for HS512; it is %d bits."
                                    .formatted(MIN_KEY_BITS, keyBytes.length * 8)
                            + " Generate one with: openssl rand -base64 48");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMillis = expirationMillis;
        this.clock = clock;
        this.parser = pinnedToHs512(Jwts.parser().verifyWith(signingKey))
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /**
     * Restricts the parser to HS512, whatever the token's header claims.
     *
     * <p>Done by removing every other algorithm rather than {@code sig().clear().add(HS512)}: in
     * jjwt 0.12.6 {@code clear()} rebuilds the registry immediately, and an empty registry throws
     * ("Collection of Identifiable instances may not be null or empty") before the add runs.
     */
    private static JwtParserBuilder pinnedToHs512(JwtParserBuilder builder) {
        var algorithms = builder.sig();
        Jwts.SIG.get().values().stream()
                .filter(algorithm -> algorithm != Jwts.SIG.HS512)
                .forEach(algorithms::remove);
        return algorithms.and();
    }

    @Override
    public IssuedToken issue(UUID subject, String email, UserRole role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusMillis(expirationMillis);

        String token =
                Jwts.builder()
                        .subject(subject.toString())
                        .claim(EMAIL_CLAIM, email)
                        .claim(ROLE_CLAIM, role.name())
                        .issuedAt(Date.from(issuedAt))
                        .expiration(Date.from(expiresAt))
                        .signWith(signingKey, Jwts.SIG.HS512)
                        .compact();

        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verifies a token and reads the caller out of it.
     *
     * <p>Package-private: only the filter verifies, and nothing in {@code core} needs to, so no
     * {@code TokenVerifier} port exists. Add one if a use case ever has to validate a token itself.
     *
     * @throws InvalidTokenException for every kind of refusal; its reason is for logging only
     */
    AuthenticatedPrincipal verify(String token) {
        Claims claims;
        try {
            claims = parser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            // jjwt checks the signature before exp, so this token was genuinely ours.
            throw new InvalidTokenException(Reason.EXPIRED, e);
        } catch (UnsupportedJwtException e) {
            // Raised both for alg: none and for any alg outside the pinned set.
            throw new InvalidTokenException(Reason.UNSUPPORTED_ALGORITHM, e);
        } catch (SecurityException e) {
            // jjwt 0.12.6 wraps an alg outside the pinned set in a SignatureException "for backwards
            // compatibility" (DefaultJwtParser.verifySignature); the cause tells the two apart.
            Reason reason =
                    e.getCause() instanceof UnsupportedJwtException
                            ? Reason.UNSUPPORTED_ALGORITHM
                            : Reason.BAD_SIGNATURE;
            throw new InvalidTokenException(reason, e);
        } catch (MalformedJwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(Reason.MALFORMED, e);
        } catch (JwtException e) {
            throw new InvalidTokenException(Reason.MALFORMED, e);
        }
        return toPrincipal(claims);
    }

    private static AuthenticatedPrincipal toPrincipal(Claims claims) {
        try {
            if (claims.getExpiration() == null) {
                throw new IllegalArgumentException("no exp claim");
            }
            String email = claims.get(EMAIL_CLAIM, String.class);
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("no email claim");
            }
            return new AuthenticatedPrincipal(
                    UUID.fromString(claims.getSubject()),
                    email,
                    UserRole.valueOf(claims.get(ROLE_CLAIM, String.class)));
        } catch (RuntimeException e) {
            // Only reachable with our own key, so this is a bug in an issuer, not an attack —
            // but it is still a refusal, never a principal with a null role.
            throw new InvalidTokenException(Reason.INVALID_CLAIMS, e);
        }
    }
}
