package com.callverse.core.application.features.admin.queries;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.application.interfaces.UserAccounts.AccountRecord;
import java.util.Objects;
import java.util.UUID;

/** One account. Serves {@code GET /api/v1/admin/users/{id}} (ADMIN). */
public class GetUserQueryHandler {

    private final UserAccounts accounts;

    public GetUserQueryHandler(UserAccounts accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    }

    public AccountRecord handle(UUID id) {
        return accounts.find(Objects.requireNonNull(id, "id must not be null"))
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }
}
