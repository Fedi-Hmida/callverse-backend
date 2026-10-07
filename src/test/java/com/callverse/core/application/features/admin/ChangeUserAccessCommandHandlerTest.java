package com.callverse.core.application.features.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.features.admin.commands.ChangeUserAccessCommand;
import com.callverse.core.application.features.admin.commands.ChangeUserAccessCommandHandler;
import com.callverse.core.application.features.admin.commands.CreateUserCommand;
import com.callverse.core.application.features.admin.commands.CreateUserCommandHandler;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.domain.enums.UserRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When access changes, what is cut and what is stored: plain unit tests over fakes. */
class ChangeUserAccessCommandHandlerTest {

    private final UUID admin = UUID.randomUUID();
    private final UUID karim = UUID.randomUUID();
    private final List<UUID> revoked = new ArrayList<>();

    /** One account whose state the fake changes like the adapter does. */
    private final class OneAccount implements UserAccounts {
        UserRole role = UserRole.ADVISOR;
        boolean active = true;
        NewAccount created;

        private AccountRecord record() {
            return new AccountRecord(karim, "karim@bank.fr", "Karim", "Benali", role, active, Instant.EPOCH);
        }

        @Override
        public AccountPage list(AccountFilter filter, int page, int size) {
            return new AccountPage(List.of(record()), page, size, 1, 1);
        }

        @Override
        public Optional<AccountRecord> find(UUID id) {
            return Optional.of(record());
        }

        @Override
        public Optional<AccountStatus> status(UUID id) {
            return Optional.of(new AccountStatus(active, role));
        }

        @Override
        public AccountRecord create(UUID actorId, NewAccount account) {
            created = account;
            return record();
        }

        @Override
        public AccountChange changeRole(UUID actorId, UUID targetId, UserRole newRole) {
            boolean changed = role != newRole;
            role = newRole;
            return new AccountChange(record(), changed);
        }

        @Override
        public AccountChange setActive(UUID actorId, UUID targetId, boolean newActive) {
            boolean changed = active != newActive;
            active = newActive;
            return new AccountChange(record(), changed);
        }
    }

    private final OneAccount account = new OneAccount();
    private final ChangeUserAccessCommandHandler handler = new ChangeUserAccessCommandHandler(account, revoked::add);

    @Test
    @DisplayName("blocking cuts the account's live sessions once; blocking again cuts nothing")
    void block() {
        handler.handle(ChangeUserAccessCommand.active(admin, karim, false));
        handler.handle(ChangeUserAccessCommand.active(admin, karim, false));
        assertThat(revoked).containsExactly(karim);
    }

    @Test
    @DisplayName("unblocking gives access back and cuts nothing")
    void unblock() {
        account.active = false;
        handler.handle(ChangeUserAccessCommand.active(admin, karim, true));
        assertThat(account.active).isTrue();
        assertThat(revoked).isEmpty();
    }

    @Test
    @DisplayName("a role change cuts the live sessions (they were opened with the old role); the same role again does not")
    void role() {
        handler.handle(ChangeUserAccessCommand.role(admin, karim, UserRole.SUPERVISOR));
        handler.handle(ChangeUserAccessCommand.role(admin, karim, UserRole.SUPERVISOR));
        assertThat(revoked).containsExactly(karim);
    }

    @Test
    @DisplayName("a command changing both or neither is VALIDATION_FAILED")
    void exactlyOne() {
        assertThatThrownBy(() -> handler.handle(new ChangeUserAccessCommand(admin, karim, UserRole.ADMIN, false)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(new ChangeUserAccessCommand(admin, karim, null, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("creating stores a lower-case address and only the hash, never the password")
    void create() {
        new CreateUserCommandHandler(account, raw -> "hashed:" + raw.length())
                .handle(new CreateUserCommand(admin, "  Karim.Benali@Bank.FR ", " Karim ", "Benali", UserRole.ADVISOR,
                        "plateau-lundi-9h30"));
        assertThat(account.created.email()).isEqualTo("karim.benali@bank.fr");
        assertThat(account.created.firstName()).isEqualTo("Karim");
        assertThat(account.created.passwordHash()).isEqualTo("hashed:18").doesNotContain("plateau");
    }

    @Test
    @DisplayName("the command never prints its password")
    void commandToString() {
        assertThat(new CreateUserCommand(admin, "a@b.c", "A", "B", UserRole.ADMIN, "plateau-lundi-9h30").toString())
                .doesNotContain("plateau");
    }
}
