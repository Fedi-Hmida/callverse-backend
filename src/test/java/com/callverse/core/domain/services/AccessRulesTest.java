package com.callverse.core.domain.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.domain.exceptions.SelfLockoutException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Who may change whose access: plain unit tests, no Spring, no database. */
class AccessRulesTest {

    private final UUID admin = UUID.randomUUID();

    @Test
    @DisplayName("an admin may not block themselves or change their own role: SELF_LOCKOUT")
    void blockSelf() {
        assertThatThrownBy(() -> AccessRules.requireNotSelf(admin, admin))
                .isInstanceOf(SelfLockoutException.class)
                .extracting("code").isEqualTo("SELF_LOCKOUT");
    }

    @Test
    @DisplayName("acting on someone else is fine")
    void other() {
        AccessRules.requireNotSelf(admin, UUID.randomUUID());
    }
}
