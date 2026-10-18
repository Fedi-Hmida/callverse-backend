package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.InvalidCredentialsException;
import com.callverse.core.application.interfaces.AuthSessions;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.domain.enums.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class AuthSessionsAdapter implements AuthSessions {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private record Session(UUID id, UUID userId, String email, UserRole role, UserRole issuedRole,
                           boolean active, Instant expiresAt, Instant revokedAt, Instant consumedAt) {}

    @Override @Transactional
    public Grant start(String email) {
        var users=jdbc.query("select id,email,role from app_user where lower(email)=lower(?) and active=true for update",
                (rs,n)->new AuthenticatedPrincipal(rs.getObject("id",UUID.class),rs.getString("email"),UserRole.valueOf(rs.getString("role"))),email);
        if(users.size()!=1) throw new InvalidCredentialsException();
        var user=users.getFirst(); var id=UUID.randomUUID(); var expires=clock.instant().plus(7,ChronoUnit.DAYS);
        jdbc.update("insert into auth_session(id,user_id,issued_role,expires_at) values(?,?,?,?)",id,user.userId(),user.role().name(),Timestamp.from(expires));
        String refresh=newToken();
        jdbc.update("insert into auth_refresh_token(token_hash,session_id) values(?,?)",hash(refresh),id);
        return new Grant(id,user,refresh,expires);
    }

    @Override @Transactional(noRollbackFor=InvalidCredentialsException.class)
    public Grant rotate(String raw) {
        if(raw==null || !raw.matches("[A-Za-z0-9_-]{43}")) throw new InvalidCredentialsException();
        var found=lookup(raw,true);
        if(found.isEmpty()) throw new InvalidCredentialsException();
        var session=found.getFirst(); var now=clock.instant();
        if(session.revokedAt()!=null || !session.expiresAt().isAfter(now)) throw new InvalidCredentialsException();
        // A separate statement AFTER the family lock sees a preceding rotation's commit,
        // including when this transaction initially waited for that same session row.
        boolean consumed=Boolean.TRUE.equals(jdbc.queryForObject("select consumed_at is not null from auth_refresh_token where token_hash=?",Boolean.class,hash(raw)));
        if(consumed || !session.active() || session.role()!=session.issuedRole()) {
            jdbc.update("update auth_session set revoked_at=? where id=?",Timestamp.from(now),session.id());
            // This refusal must COMMIT the family revocation, including replay of an old token.
            throw new InvalidCredentialsException();
        }
        jdbc.update("update auth_refresh_token set consumed_at=? where token_hash=?",Timestamp.from(now),hash(raw));
        String replacement=newToken();
        jdbc.update("insert into auth_refresh_token(token_hash,session_id) values(?,?)",hash(replacement),session.id());
        return new Grant(session.id(),new AuthenticatedPrincipal(session.userId(),session.email(),session.role()),replacement,session.expiresAt());
    }

    @Override @Transactional
    public Optional<UUID> revoke(String raw) {
        if(raw==null || !raw.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        var found=lookup(raw,true);
        if(found.isEmpty()) return Optional.empty();
        var session=found.getFirst();
        jdbc.update("update auth_session set revoked_at=coalesce(revoked_at,?) where id=?",Timestamp.from(clock.instant()),session.id());
        return Optional.of(session.userId());
    }

    @Override
    public boolean active(UUID sessionId,UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from auth_session where id=? and user_id=? and revoked_at is null and expires_at>?)",
                Boolean.class,sessionId,userId,Timestamp.from(clock.instant())));
    }

    private List<Session> lookup(String raw,boolean lock) {
        return jdbc.query("""
            select s.id,s.user_id,s.issued_role,s.expires_at,s.revoked_at,t.consumed_at,u.email,u.role,u.active
            from auth_refresh_token t join auth_session s on s.id=t.session_id join app_user u on u.id=s.user_id
            where t.token_hash=?
            """+(lock?" for update of s,u":""),(rs,n)->new Session(rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),
                rs.getString("email"),UserRole.valueOf(rs.getString("role")),UserRole.valueOf(rs.getString("issued_role")),
                rs.getBoolean("active"),rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at")==null?null:rs.getTimestamp("revoked_at").toInstant(),
                rs.getTimestamp("consumed_at")==null?null:rs.getTimestamp("consumed_at").toInstant()),hash(raw));
    }
    private static String newToken() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
