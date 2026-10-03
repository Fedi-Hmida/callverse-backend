package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.exceptions.EmailAlreadyUsedException;
import com.callverse.core.domain.services.AccessRules;
import com.callverse.infrastructure.persistence.repositories.AppUserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link UserAccounts} with Spring Data.
 *
 * <p><strong>Lock order: the active administrators (in id order), then the target.</strong> Every
 * change takes the same order, so concurrent changes queue instead of deadlocking, and the actor's
 * own status is read under the lock — an administrator removed a moment ago is refused, not obeyed.
 */
@Component
@RequiredArgsConstructor
class UserAccountsAdapter implements UserAccounts {

    /** PostgreSQL's default name for {@code app_user.email UNIQUE} ({@code V1__init.sql}). */
    private static final String EMAIL_UNIQUE_INDEX = "app_user_email_key";

    private final AppUserRepository users;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public AccountPage list(AccountFilter filter, int page, int size) {
        Page<AppUser> found = users.search(filter.role(), filter.active(), pattern(filter.query()),
                PageRequest.of(page, size));
        return new AccountPage(found.map(UserAccountsAdapter::toRecord).getContent(), page, size,
                found.getTotalElements(), found.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountRecord> find(UUID id) {
        return users.findById(id).map(UserAccountsAdapter::toRecord);
    }

    /**
     * Read on every authenticated request: two columns, no entity, no read-only transaction around
     * it (which would cost a SET plus BEGIN and COMMIT round trips to Neon each time).
     */
    @Override
    public Optional<AccountStatus> status(UUID id) {
        return users.findStatusById(id).map(s -> new AccountStatus(s.getActive(), s.getRole()));
    }

    @Override
    @Transactional
    public AccountRecord create(UUID actorId, NewAccount account) {
        requireActiveAdmin(actorId, users.lockActiveByRole(UserRole.ADMIN));
        if (users.existsByEmailIgnoreCase(account.email())) {
            throw new EmailAlreadyUsedException();
        }
        AppUser user = new AppUser();
        user.setEmail(account.email());
        user.setFirstName(account.firstName());
        user.setLastName(account.lastName());
        user.setRole(account.role());
        user.setPasswordHash(account.passwordHash());
        user.setActive(true);
        // Microseconds: PostgreSQL's precision, so the record returned equals the row read back.
        user.setCreatedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        try {
            return toRecord(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException violation) {
            // Two creations of the same address at once: the unique index decides, the loser is told.
            // Any other constraint is a real error and is not disguised as a taken address.
            if (String.valueOf(violation.getMostSpecificCause().getMessage()).contains(EMAIL_UNIQUE_INDEX)) {
                throw new EmailAlreadyUsedException();
            }
            throw violation;
        }
    }

    @Override
    @Transactional
    public AccountChange changeRole(UUID actorId, UUID targetId, UserRole role) {
        return change(actorId, targetId, u -> u.getRole() == role, u -> u.setRole(role));
    }

    @Override
    @Transactional
    public AccountChange setActive(UUID actorId, UUID targetId, boolean active) {
        return change(actorId, targetId, u -> u.isActive() == active, u -> u.setActive(active));
    }

    private AccountChange change(
            UUID actorId, UUID targetId, Predicate<AppUser> alreadyThere, Consumer<AppUser> apply) {
        requireActiveAdmin(actorId, users.lockActiveByRole(UserRole.ADMIN));
        AccessRules.requireNotSelf(actorId, targetId);
        AppUser target = users.findByIdForUpdate(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("User", targetId));
        if (alreadyThere.test(target)) {
            return new AccountChange(toRecord(target), false);
        }
        apply.accept(target);
        return new AccountChange(toRecord(target), true);
    }

    /**
     * The actor must be among the active administrators just locked: an administrator blocked or
     * demoted a moment ago is refused, whatever their token said when the request began.
     */
    private static void requireActiveAdmin(UUID actorId, List<AppUser> lockedActiveAdmins) {
        if (lockedActiveAdmins.stream().noneMatch(a -> a.getId().equals(actorId))) {
            throw new ActionNotPermittedException("Your administrator access is no longer active.");
        }
    }

    /** Lower-case LIKE pattern with the user's wildcards escaped, or null for "any". */
    private static String pattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String escaped = query.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static AccountRecord toRecord(AppUser u) {
        return new AccountRecord(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getRole(),
                u.isActive(), u.getCreatedAt());
    }
}
