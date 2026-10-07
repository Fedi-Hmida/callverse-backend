package com.callverse.infrastructure.security;

import static com.callverse.auth.AuthenticatedRequests.tokenForExistingAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.callverse.core.application.features.conversation.queries.ConversationSubscriptionPolicy;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * The race between a CONNECT and a block: the account is read as active, then the block commits and
 * revokes before the new session is known. The session must still end up cut. Plain unit test: the
 * block is simulated inside the account read, at the worst possible moment.
 */
class StompConnectRevocationRaceTest {

    @Test
    @DisplayName("a block landing between the CONNECT's account read and its registration still cuts the session")
    void blockDuringConnect() {
        UUID sarah = UUID.randomUUID();
        StompSessionRegistry sessions = new StompSessionRegistry(Clock.systemUTC());
        UserAccounts blockedWhileReading = new UserAccounts() {
            @Override
            public Optional<AccountStatus> status(UUID id) {
                sessions.revoke(id);                                   // the admin's block commits now...
                return Optional.of(new AccountStatus(true, UserRole.SUPERVISOR)); // ...after this row was read
            }

            @Override public AccountPage list(AccountFilter f, int p, int s) { return new AccountPage(List.of(), p, s, 0, 0); }
            @Override public Optional<AccountRecord> find(UUID id) { return Optional.empty(); }
            @Override public AccountRecord create(UUID actorId, NewAccount a) { throw new UnsupportedOperationException(); }
            @Override public AccountChange changeRole(UUID a, UUID t, UserRole r) { throw new UnsupportedOperationException(); }
            @Override public AccountChange setActive(UUID a, UUID t, boolean v) { throw new UnsupportedOperationException(); }
        };
        StompAuthorizationInterceptor interceptor = new StompAuthorizationInterceptor(
                new JwtTokenService(AbstractPersistenceTest.TEST_JWT_SECRET, 3_600_000, Clock.systemUTC()),
                sessions,
                mock(ConversationSubscriptionPolicy.class),
                new AccountGate(blockedWhileReading));

        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("session-1");
        connect.addNativeHeader("Authorization",
                "Bearer " + tokenForExistingAccount(sarah, "sarah@bank.test", UserRole.SUPERVISOR));
        connect.setLeaveMutable(true);
        Message<byte[]> frame = MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());

        interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(sessions.isExpiredOrUnknown("session-1"))
                .as("the session registered by this CONNECT must be cut by the block").isTrue();
    }
}
