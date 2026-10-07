package com.callverse.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.persistence.AbstractPersistenceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;

/**
 * Accounts live in {@code app_user} and are checked by the login endpoint; a JWT is the only
 * credential the API accepts. Spring Boot's default in-memory user, with its random "generated
 * security password", must not exist: it is unusable here and it reads like a second way in.
 */
@ActiveProfiles("prod")
class NoDefaultUserStoreTest extends AbstractPersistenceTest {

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("there is no UserDetailsService, so no generated password and no form-login user")
    void noDefaultUserStore() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }
}
