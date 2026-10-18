package com.callverse.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.callverse.core.application.features.conversation.queries.ConversationSubscriptionPolicy;
import com.callverse.core.application.interfaces.AuthSessions;
import com.callverse.core.domain.enums.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class StompRefreshRevocationTest {
    private static final String SECRET = "test-only-hs512-signing-key-not-used-for-anything-real-0123456789abcdef";
    private final AuthSessions families = mock(AuthSessions.class);
    private final StompSessionRegistry registry = new StompSessionRegistry(Clock.systemUTC(), families);

    @Test void replayRevocationStopsMessagesOnAnAlreadyConnectedSocket() {
        UUID family = UUID.randomUUID(), user = UUID.randomUUID();
        when(families.active(family, user)).thenReturn(true, false);
        registry.register("socket", user, Instant.now().plusSeconds(60), family);
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("socket");
        var frame = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertThat(registry.preSend(frame, mock(MessageChannel.class))).isSameAs(frame);
        assertThat(registry.preSend(frame, mock(MessageChannel.class))).isNull();
        assertThat(registry.isExpiredOrUnknown("socket")).isTrue();
    }

    @Test void logoutBetweenJwtVerificationAndRegistrationRefusesConnect() {
        UUID family = UUID.randomUUID(), user = UUID.randomUUID();
        when(families.active(family, user)).thenReturn(true, false);
        JwtTokenService issuer = new JwtTokenService(SECRET, 60_000, Clock.systemUTC(), families);
        String jwt = issuer.issue(user, "test@bank.test", UserRole.SUPERVISOR, family).value();
        var interceptor = new StompAuthorizationInterceptor(issuer, registry,
                mock(ConversationSubscriptionPolicy.class), mock(AccountGate.class));
        var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("socket");
        connect.addNativeHeader("Authorization", "Bearer " + jwt);
        connect.setLeaveMutable(true);
        var frame = MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
        assertThatThrownBy(() -> interceptor.preSend(frame, mock(MessageChannel.class)))
                .isInstanceOf(MessagingException.class);
        assertThat(registry.isExpiredOrUnknown("socket")).isTrue();
    }
}
