package com.callverse.core.application.features.admin.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.AccessRevocation;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.application.interfaces.UserAccounts.AccountChange;
import java.util.Objects;

/**
 * Changes an account's role, blocks it or unblocks it.
 *
 * <p>Serves {@code PUT /api/v1/admin/users/{id}/role}, {@code POST .../block} and
 * {@code POST .../unblock} (ADMIN). The rules (an active administrator acts, never on themselves) are
 * applied by the adapter under its locks.
 *
 * <p><strong>Effective at once.</strong> REST needs nothing more: every request re-reads the account,
 * so a blocked or re-roled user is refused on their next call. Their open live sessions are cut here,
 * after the change has committed. Repeating a change that is already in place changes nothing and
 * cuts nothing.
 */
public class ChangeUserAccessCommandHandler {

    private final UserAccounts accounts;
    private final AccessRevocation revocation;

    public ChangeUserAccessCommandHandler(UserAccounts accounts, AccessRevocation revocation) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.revocation = Objects.requireNonNull(revocation, "revocation must not be null");
    }

    public AccountChange handle(ChangeUserAccessCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if ((command.role() == null) == (command.active() == null)) {
            throw new InvalidRequestException("change either the role or the access, not both");
        }
        AccountChange change = command.role() != null
                ? accounts.changeRole(command.actorId(), command.targetId(), command.role())
                : accounts.setActive(command.actorId(), command.targetId(), command.active());
        boolean withdrawsSomething = command.role() != null || !command.active();
        if (change.changed() && withdrawsSomething) {
            revocation.revoke(command.targetId());
        }
        return change;
    }
}
