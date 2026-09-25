package com.callverse.infrastructure.security;

import com.callverse.core.application.interfaces.IssuedToken;
import com.callverse.core.application.interfaces.TokenIssuer;
import com.callverse.core.domain.enums.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs access tokens with HMAC-SHA256.
 *
 * <p><strong>This class is what finally activates the documented fail-fast on {@code JWT_SECRET}.</strong>
 * {@code application.yml} declares {@code jwt.secret: ${JWT_SECRET}} with no default, on purpose,
 * but until now no code read the property — so the placeholder was never resolved and the
 * application started happily without a signing key. Reading it here means a missing
 * {@code JWT_SECRET} is now a startup failure rather than a surprise at the first login.
 *
 * <p>A second guard sits behind it: {@link Keys#hmacShaKeyFor} rejects any key shorter than 256
 * bits, so a present-but-too-short secret also fails at startup rather than producing tokens that
 * are trivially forgeable. Both failures happen while constructing the bean, which is the earliest
 * moment they can.
 *
 * <p><strong>What the token carries, and what it must never carry.</strong> Subject, email and
 * role — nothing else. In particular not the password hash, and not any field that the client could
 * mistake for authorisation state. The server re-reads the role from the verified token on every
 * request; the copy returned alongside the token is a convenience for choosing which UI to render,
 * never a trusted input.
 *
 * <p>Validating tokens is not here. That is a filter, and it is sub-phase 2.2.
 */
@Component
public class JwtTokenService implements TokenIssuer {

    private final SecretKey signingKey;
    private final long expirationMillis;
    private final Clock clock;

    JwtTokenService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMillis,
            Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = expirationMillis;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(UUID subject, String email, UserRole role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusMillis(expirationMillis);

        String token =
                Jwts.builder()
                        .subject(subject.toString())
                        .claim("email", email)
                        .claim("role", role.name())
                        .issuedAt(Date.from(issuedAt))
                        .expiration(Date.from(expiresAt))
                        .signWith(signingKey)
                        .compact();

        return new IssuedToken(token, expiresAt);
    }
}
