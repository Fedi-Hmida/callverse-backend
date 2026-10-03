package com.callverse.core.application.features.admin.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.application.interfaces.UserAccounts.AccountFilter;
import com.callverse.core.application.interfaces.UserAccounts.AccountPage;
import java.util.Objects;

/** The administrator's account list, newest first. Serves {@code GET /api/v1/admin/users} (ADMIN). */
public class ListUsersQueryHandler {

    public static final int MAX_SIZE = 100;

    private final UserAccounts accounts;

    public ListUsersQueryHandler(UserAccounts accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    }

    public AccountPage handle(ListUsersQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.page() < 0 || query.size() < 1 || query.size() > MAX_SIZE) {
            throw new InvalidRequestException("page must be 0 or more, size 1 to %d".formatted(MAX_SIZE));
        }
        String text = query.query() == null ? null : query.query().strip();
        if (text != null && text.length() > 100) {
            throw new InvalidRequestException("q must be at most 100 characters");
        }
        return accounts.list(new AccountFilter(query.role(), query.active(), text), query.page(), query.size());
    }
}
