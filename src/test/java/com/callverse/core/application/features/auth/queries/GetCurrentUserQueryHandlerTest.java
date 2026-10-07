package com.callverse.core.application.features.auth.queries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.domain.enums.UserRole;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The "who am I" use case, with the principal port replaced by a lambda. */
class GetCurrentUserQueryHandlerTest {

    @Test
    @DisplayName("returns the caller the port reports")
    void returnsTheCurrentPrincipal() {
        AuthenticatedPrincipal caller =
                new AuthenticatedPrincipal(UUID.randomUUID(), "customer@callverse.local", UserRole.CUSTOMER);

        assertThat(new GetCurrentUserQueryHandler(() -> Optional.of(caller)).handle(new GetCurrentUserQuery()))
                .isEqualTo(caller);
    }

    @Test
    @DisplayName("an anonymous caller is refused with UNAUTHENTICATED, not answered with nulls")
    void anonymousCallerIsRefused() {
        GetCurrentUserQueryHandler handler = new GetCurrentUserQueryHandler(Optional::empty);

        assertThatThrownBy(() -> handler.handle(new GetCurrentUserQuery()))
                .isInstanceOfSatisfying(
                        AuthenticationRequiredException.class,
                        e -> assertThat(e.code()).isEqualTo("UNAUTHENTICATED"));
    }
}
