package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The accounts an administrator manages, and the current status every authenticated request is
 * checked against.
 *
 * <p><strong>Each change is one transaction that locks before it decides.</strong> The active
 * administrators are locked first, in id order, then the target: the actor's own administrator
 * status is re-read under that lock ({@code AccessRules}), so two administrators removing each other
 * at the same instant are serialized and the second is refused.
 *
 * <p>Accounts are deactivated, never deleted: escalations, credits and decisions keep pointing at
 * the people who acted.
 */
public interface UserAccounts {

    AccountPage list(AccountFilter filter, int page, int size);

    Optional<AccountRecord> find(UUID id);

    /** What a token's holder is allowed right now: read on every authenticated request. */
    Optional<AccountStatus> status(UUID id);

    /**
     * @param actorId the administrator creating it, re-checked under the lock like every change
     * @throws com.callverse.core.application.exceptions.ActionNotPermittedException when the actor is
     *     no longer an active administrator
     * @throws com.callverse.core.domain.exceptions.EmailAlreadyUsedException for a taken address
     */
    AccountRecord create(UUID actorId, NewAccount account);

    /**
     * @throws com.callverse.core.application.exceptions.ActionNotPermittedException when the actor is
     *     no longer an active administrator
     * @throws com.callverse.core.domain.exceptions.SelfLockoutException on the actor's own account
     */
    AccountChange changeRole(UUID actorId, UUID targetId, UserRole role);

    /** Same rules as {@link #changeRole}. */
    AccountChange setActive(UUID actorId, UUID targetId, boolean active);

    /** Never the password hash. */
    record AccountRecord(
            UUID id, String email, String firstName, String lastName, UserRole role, boolean active, Instant createdAt) {}

    record AccountStatus(boolean active, UserRole role) {}

    /** Every field is optional; {@code query} matches the email or a name, case-insensitively. */
    record AccountFilter(UserRole role, Boolean active, String query) {}

    record AccountPage(List<AccountRecord> content, int page, int size, long totalElements, int totalPages) {

        public AccountPage {
            content = List.copyOf(content);
        }
    }

    /** @param passwordHash already hashed: the plaintext never reaches a port */
    record NewAccount(String email, String firstName, String lastName, UserRole role, String passwordHash) {}

    /** @param changed false when the account already had that role or status */
    record AccountChange(AccountRecord account, boolean changed) {}
}
