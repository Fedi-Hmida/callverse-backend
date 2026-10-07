package com.callverse.core.application.features.admin.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.PasswordHasher;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.application.interfaces.UserAccounts.AccountRecord;
import com.callverse.core.application.interfaces.UserAccounts.NewAccount;
import com.callverse.core.domain.services.PasswordPolicy;
import java.util.Locale;
import java.util.Objects;

/**
 * Creates an active account with a role and a temporary password.
 *
 * <p>Serves {@code POST /api/v1/admin/users} (ADMIN). The address is stored trimmed and in lower
 * case, so {@code Karim@Bank.fr} and {@code karim@bank.fr} are one account. The password must satisfy
 * {@link PasswordPolicy}; it is hashed here and only the hash travels further.
 */
public class CreateUserCommandHandler {

    private final UserAccounts accounts;
    private final PasswordHasher hasher;

    public CreateUserCommandHandler(UserAccounts accounts, PasswordHasher hasher) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
    }

    public AccountRecord handle(CreateUserCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.role() == null || blank(command.email()) || blank(command.firstName()) || blank(command.lastName())) {
            throw new InvalidRequestException("email, firstName, lastName and role are required");
        }
        String email = command.email().strip().toLowerCase(Locale.ROOT);
        PasswordPolicy.violation(command.password(), email).ifPresent(reason -> {
            throw new InvalidRequestException(reason);
        });
        return accounts.create(command.actorId(), new NewAccount(email, command.firstName().strip(), command.lastName().strip(),
                command.role(), hasher.hash(command.password())));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
