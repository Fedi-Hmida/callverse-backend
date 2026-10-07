package com.callverse.infrastructure.security;

import com.callverse.core.application.features.conversation.queries.ConversationSubscriptionPolicy;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.domain.enums.UserRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * Security for the live channel: the third mechanism next to the HTTP chains and
 * {@code @PreAuthorize}, which do not see STOMP frames.
 *
 * <p><strong>An allowlist of commands, not a blocklist.</strong> Clients listen; the server speaks.
 *
 * <ul>
 *   <li><strong>CONNECT</strong> and its STOMP 1.2 alias <strong>STOMP</strong> must carry
 *       {@code Authorization: Bearer <jwt>} as a STOMP header, verified by the same
 *       {@link JwtTokenService} as REST, for an account that is still active with that role
 *       ({@link AccountGate}). The user and the token's expiry are attached to the session.
 *   <li><strong>SUBSCRIBE</strong> needs an authenticated, unexpired session and a destination from
 *       the fixed table of the five frozen topics, for a role allowed on it. A destination containing
 *       a wildcard or a path trick is refused: one subscription may not cover many conversations.
 *   <li><strong>UNSUBSCRIBE</strong> and <strong>DISCONNECT</strong> only affect the caller's own
 *       session and pass.
 *   <li><strong>Every other frame is refused</strong> — SEND, and also MESSAGE, which a client could
 *       otherwise use to publish straight to subscribers, plus ACK, NACK, BEGIN, COMMIT, ABORT and the
 *       server-only frames.
 * </ul>
 *
 * <p>A refusal throws, so Spring answers with a STOMP ERROR frame ("Access denied") and closes the
 * session. The log line names the command and the sanitized destination, never the token.
 *
 * <p><strong>Ownership, after the role.</strong> A role allowed on a topic is necessary, not
 * sufficient. On {@code /topic/conversation/{id}} the caller must be allowed to read that very
 * conversation — its customer, its assigned advisor, or a supervisor (rule A15). On
 * {@code /topic/queue/{skill}} an advisor must hold the skill (rule B14). Both questions are asked of
 * {@link ConversationSubscriptionPolicy}, the same rules the REST reads use, so the live topic can
 * never be a second, wider door.
 */
@Component
@Slf4j
public class StompAuthorizationInterceptor implements ChannelInterceptor {

    private static final Set<UserRole> SUPERVISION = Set.of(UserRole.SUPERVISOR, UserRole.ADMIN);
    private static final Set<UserRole> STAFF = Set.of(UserRole.ADVISOR, UserRole.SUPERVISOR, UserRole.ADMIN);
    private static final Set<UserRole> ANYONE = Set.of(UserRole.values());

    private static final String QUEUE = "/topic/queue/*";
    private static final String CONVERSATION = "/topic/conversation/*";

    /**
     * The five frozen topics (brief 01 :67) and who may listen to each. Nothing else exists. The queue
     * and conversation topics then check ownership on top of the role.
     */
    private static final Map<String, Set<UserRole>> TOPICS = Map.of(
            "/topic/supervision/alerts", SUPERVISION,
            "/topic/supervision/kpi", SUPERVISION,
            "/topic/runs/*", SUPERVISION,
            QUEUE, STAFF,
            CONVERSATION, ANYONE);

    /**
     * Literal segments only: letters, digits, '-' and '_'. No '*', '?', '{', '}' (which the broker
     * would read as a pattern), no '..', no empty segment, no trailing slash.
     */
    private static final Pattern LITERAL_DESTINATION = Pattern.compile("(/[A-Za-z0-9_-]{1,64}){2,4}");

    private final AntPathMatcher paths = new AntPathMatcher();
    private final JwtTokenService tokens;
    private final StompSessionRegistry sessions;
    private final ConversationSubscriptionPolicy subscriptions;
    private final AccountGate accounts;

    public StompAuthorizationInterceptor(
            JwtTokenService tokens,
            StompSessionRegistry sessions,
            ConversationSubscriptionPolicy subscriptions,
            AccountGate accounts) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.subscriptions = subscriptions;
        this.accounts = accounts;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null) {
            return message;
        }
        if (stomp.getMessageType() == SimpMessageType.MESSAGE) {
            // SEND and MESSAGE frames both arrive as MESSAGE: whatever the command, a client never publishes.
            throw refuse(stomp, "clients may not publish");
        }
        StompCommand command = stomp.getCommand();
        if (command == null) {
            return message; // a heartbeat
        }
        switch (command) {
            case CONNECT, STOMP -> authenticate(stomp);
            case SUBSCRIBE -> authorizeSubscription(stomp);
            case UNSUBSCRIBE, DISCONNECT -> {
                // only ever affects the caller's own session
            }
            default -> throw refuse(stomp, "command not allowed from a client");
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor stomp) {
        String header = stomp.getFirstNativeHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw refuse(stomp, "no bearer token on CONNECT");
        }
        JwtTokenService.VerifiedToken verified;
        try {
            verified = tokens.verifyWithExpiry(header.substring(7).trim());
        } catch (InvalidTokenException refused) {
            throw refuse(stomp, "token refused: " + refused.reason());
        }
        // Registered before the account is read: a block that commits after this read still finds
        // the session and cuts it. Read first, register second would leave a gap where the block's
        // revocation runs before the session exists, and the session would live until its token expires.
        sessions.register(stomp.getSessionId(), verified.principal().userId(), verified.expiresAt());
        try {
            accounts.confirm(verified.principal());
        } catch (InvalidTokenException refused) {
            sessions.unregister(stomp.getSessionId());
            throw refuse(stomp, "token refused: " + refused.reason());
        }
        stomp.setUser(UsernamePasswordAuthenticationToken.authenticated(
                verified.principal(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + verified.principal().role().name()))));
    }

    private void authorizeSubscription(StompHeaderAccessor stomp) {
        if (!(stomp.getUser() instanceof UsernamePasswordAuthenticationToken user)
                || !(user.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            throw refuse(stomp, "not authenticated");
        }
        if (sessions.isExpiredOrUnknown(stomp.getSessionId())) {
            throw refuse(stomp, "token expired since CONNECT; reconnect with a fresh token");
        }
        String destination = stomp.getDestination();
        if (destination == null || !LITERAL_DESTINATION.matcher(destination).matches()) {
            throw refuse(stomp, "destination is not a literal path");
        }
        Set<UserRole> allowed = TOPICS.entrySet().stream()
                .filter(topic -> paths.match(topic.getKey(), destination))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (allowed == null) {
            throw refuse(stomp, "unknown destination");
        }
        if (!allowed.contains(principal.role())) {
            throw refuse(stomp, "role " + principal.role() + " may not subscribe here");
        }
        String lastSegment = destination.substring(destination.lastIndexOf('/') + 1);
        if (paths.match(CONVERSATION, destination) && !subscriptions.mayListenToConversation(principal, lastSegment)) {
            throw refuse(stomp, "not a reader of this conversation");
        }
        if (paths.match(QUEUE, destination) && !subscriptions.mayListenToQueue(principal, lastSegment)) {
            throw refuse(stomp, "holds no skill for this queue");
        }
    }

    private static MessagingException refuse(StompHeaderAccessor stomp, String why) {
        log.warn("STOMP {} refused on '{}': {}", stomp.getCommand(), sanitize(stomp.getDestination()), why);
        return new MessagingException("Access denied");
    }

    /** The destination is client input: no control characters (log forging), at most 120 characters. */
    private static String sanitize(String value) {
        if (value == null) {
            return "-";
        }
        String clean = value.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > 120 ? clean.substring(0, 120) + "..." : clean;
    }
}
