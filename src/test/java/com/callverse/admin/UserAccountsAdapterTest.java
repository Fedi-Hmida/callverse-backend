package com.callverse.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.application.interfaces.UserAccounts.AccountChange;
import com.callverse.core.application.interfaces.UserAccounts.AccountFilter;
import com.callverse.core.application.interfaces.UserAccounts.AccountPage;
import com.callverse.core.application.interfaces.UserAccounts.AccountRecord;
import com.callverse.core.application.interfaces.UserAccounts.NewAccount;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.exceptions.EmailAlreadyUsedException;
import com.callverse.core.domain.exceptions.SelfLockoutException;
import com.callverse.persistence.AbstractPersistenceTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Account administration against a real PostgreSQL: uniqueness, the lock-then-decide rules and the
 * race between two administrators. The adapter commits its own transactions, as in production.
 */
class UserAccountsAdapterTest extends AbstractPersistenceTest {

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuJ4H1y9oD6kBz1V5Q2yQ1w5bLb6xXyZ2";

    @Autowired UserAccounts accounts;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private AccountRecord account(UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return accounts.create(seededAdmin(), new NewAccount("user-" + suffix + "@bank.test", "Test", role.name(), role, HASH));
    }

    @Test
    @DisplayName("create stores an active account and never exposes the hash")
    void create() {
        AccountRecord created = account(UserRole.ADVISOR);

        assertThat(created.active()).isTrue();
        assertThat(created.role()).isEqualTo(UserRole.ADVISOR);
        assertThat(accounts.find(created.id())).contains(created);
        assertThat(accounts.status(created.id())).hasValueSatisfying(s -> {
            assertThat(s.active()).isTrue();
            assertThat(s.role()).isEqualTo(UserRole.ADVISOR);
        });
    }

    @Test
    @DisplayName("an address already taken, in any case, is EMAIL_ALREADY_USED")
    void duplicateEmail() {
        AccountRecord first = account(UserRole.CUSTOMER);

        assertThatThrownBy(() -> accounts.create(seededAdmin(), new NewAccount(
                        first.email().toUpperCase(), "Other", "Person", UserRole.ADVISOR, HASH)))
                .isInstanceOf(EmailAlreadyUsedException.class);
    }

    @Test
    @DisplayName("blocking and changing a role are applied; repeating them changes nothing")
    void changes() {
        AccountRecord admin = account(UserRole.ADMIN);
        AccountRecord karim = account(UserRole.ADVISOR);

        AccountChange blocked = accounts.setActive(admin.id(), karim.id(), false);
        assertThat(blocked.changed()).isTrue();
        assertThat(blocked.account().active()).isFalse();
        assertThat(accounts.setActive(admin.id(), karim.id(), false).changed()).isFalse();

        AccountChange promoted = accounts.changeRole(admin.id(), karim.id(), UserRole.SUPERVISOR);
        assertThat(promoted.changed()).isTrue();
        assertThat(accounts.status(karim.id()).orElseThrow().role()).isEqualTo(UserRole.SUPERVISOR);
        assertThat(accounts.changeRole(admin.id(), karim.id(), UserRole.SUPERVISOR).changed()).isFalse();
    }

    @Test
    @DisplayName("an admin acting on their own account is SELF_LOCKOUT, and nothing changes")
    void selfLockout() {
        AccountRecord admin = account(UserRole.ADMIN);

        assertThatThrownBy(() -> accounts.setActive(admin.id(), admin.id(), false))
                .isInstanceOf(SelfLockoutException.class);
        assertThatThrownBy(() -> accounts.changeRole(admin.id(), admin.id(), UserRole.CUSTOMER))
                .isInstanceOf(SelfLockoutException.class);
        assertThat(accounts.status(admin.id()).orElseThrow().active()).isTrue();
    }

    @Test
    @DisplayName("an admin blocked a moment ago cannot finish a change: the actor is re-checked under the lock")
    void blockedActor() {
        AccountRecord sarah = account(UserRole.ADMIN);
        AccountRecord omar = account(UserRole.ADMIN);
        AccountRecord karim = account(UserRole.ADVISOR);
        accounts.setActive(sarah.id(), omar.id(), false);

        assertThatThrownBy(() -> accounts.setActive(omar.id(), karim.id(), false))
                .isInstanceOf(ActionNotPermittedException.class);
        assertThat(accounts.status(karim.id()).orElseThrow().active()).isTrue();
    }

    @Test
    @DisplayName("an admin blocked a moment ago cannot create an account either, not even another admin")
    void blockedActorCannotCreate() {
        AccountRecord sarah = account(UserRole.ADMIN);
        AccountRecord omar = account(UserRole.ADMIN);
        accounts.setActive(sarah.id(), omar.id(), false);
        String email = "backdoor-" + UUID.randomUUID().toString().substring(0, 8) + "@bank.test";

        assertThatThrownBy(() -> accounts.create(omar.id(), new NewAccount(email, "Back", "Door", UserRole.ADMIN, HASH)))
                .isInstanceOf(ActionNotPermittedException.class);
        assertThat(jdbc.queryForObject("select count(*) from app_user where email = ?", Long.class, email)).isZero();
    }

    @Test
    @DisplayName("two admins removing each other at the same instant: exactly one succeeds, one admin remains")
    void mutualRemoval() throws Exception {
        AccountRecord a = account(UserRole.ADMIN);
        AccountRecord b = account(UserRole.ADMIN);

        List<Object> outcomes = race(
                () -> accounts.changeRole(a.id(), b.id(), UserRole.CUSTOMER),
                () -> accounts.setActive(b.id(), a.id(), false));

        assertThat(outcomes).filteredOn(o -> o instanceof AccountChange).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof ActionNotPermittedException).hasSize(1);
        boolean aAdmin = isActiveAdmin(a.id());
        boolean bAdmin = isActiveAdmin(b.id());
        assertThat(aAdmin ^ bAdmin).as("exactly one of the two is still an active admin").isTrue();
    }

    @Test
    @DisplayName("an unknown target is RESOURCE_NOT_FOUND")
    void unknownTarget() {
        AccountRecord admin = account(UserRole.ADMIN);
        assertThatThrownBy(() -> accounts.setActive(admin.id(), UUID.randomUUID(), false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the list filters by role, status and text, newest first, paged")
    void list() {
        String tag = "zq" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 3; i++) {
            accounts.create(seededAdmin(), new NewAccount(tag + i + "@bank.test", "Lina", "Tag", UserRole.SUPERVISOR, HASH));
        }
        AccountRecord admin = account(UserRole.ADMIN);
        AccountRecord blocked = accounts.create(seededAdmin(), new NewAccount(tag + "x@bank.test", "Omar", "Tag", UserRole.ADVISOR, HASH));
        accounts.setActive(admin.id(), blocked.id(), false);

        AccountPage page0 = accounts.list(new AccountFilter(null, null, tag.toUpperCase()), 0, 2);
        assertThat(page0.totalElements()).isEqualTo(4);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content().get(0).email()).isEqualTo(tag + "x@bank.test");

        assertThat(accounts.list(new AccountFilter(UserRole.SUPERVISOR, null, tag), 0, 20).totalElements()).isEqualTo(3);
        assertThat(accounts.list(new AccountFilter(null, false, tag), 0, 20).content())
                .extracting(AccountRecord::id).containsExactly(blocked.id());
        assertThat(accounts.list(new AccountFilter(null, null, "%"), 0, 20).totalElements())
                .as("a % in the text is a literal, not a wildcard").isZero();
    }

    /** The development admin seeded by V2: an active administrator who creates the fixtures. */
    private UUID seededAdmin() {
        return jdbc.queryForObject("select id from app_user where email = 'admin@callverse.local'", UUID.class);
    }

    private boolean isActiveAdmin(UUID id) {
        return accounts.status(id).map(s -> s.active() && s.role() == UserRole.ADMIN).orElse(false);
    }

    @SafeVarargs
    private static List<Object> race(Callable<?>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
