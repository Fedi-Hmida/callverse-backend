package com.callverse.core.application.interfaces;

import com.callverse.core.domain.entities.AppUser;
import java.util.Optional;

/**
 * Looks up accounts that are permitted to authenticate.
 *
 * <p><strong>Why this port exists rather than injecting the repository.</strong> Spring Data
 * repositories live in {@code infrastructure.persistence.repositories}, and the ArchUnit rule
 * {@code core_must_not_depend_on_outer_layers} forbids anything under {@code core} from depending
 * on {@code infrastructure}. A use case that injected {@code AppUserRepository} directly would fail
 * the build. The adapter on the other side of this interface is free to use Spring Data.
 *
 * <p><strong>"Active" is part of the contract, not a caller's responsibility.</strong>
 * {@code V1__init.sql:45} states that inactive users are never routed to or authenticated. Putting
 * the filter in the method name rather than leaving it to each caller means a future use case
 * cannot forget it — there is no method here that returns a deactivated account.
 */
public interface AppUserDirectory {

    /**
     * Finds an <em>active</em> account by email, case-insensitively.
     *
     * @return the account, or empty if no active account has that address
     */
    Optional<AppUser> findActiveByEmail(String email);
}
