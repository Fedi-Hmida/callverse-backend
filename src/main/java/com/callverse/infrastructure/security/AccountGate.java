package com.callverse.infrastructure.security;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.application.interfaces.UserAccounts.AccountStatus;
import org.springframework.stereotype.Component;

/**
 * Confirms that the account behind a verified token may still act, as the token says.
 *
 * <p>A signature proves who issued the token, not that its holder still has access. So every REST
 * request (the JWT filter) and every live connection (STOMP CONNECT) re-reads the account: unknown,
 * blocked, or holding another role than the token claims — refused, on the next request, without
 * waiting for the token to expire. A user whose role changed logs in again and gets a token for the
 * role they now hold; an old token can never carry a privilege that was withdrawn.
 *
 * <p>The cost is one primary-key read per request, which is the price of revocation without a
 * revocable refresh-token scheme. Unblocking an account makes its earlier, unexpired tokens valid
 * again: revocation follows the account's status, not the token's issue time (the schema has no
 * "changed at" column to compare against).
 */
@Component
class AccountGate {

    private final UserAccounts accounts;

    AccountGate(UserAccounts accounts) {
        this.accounts = accounts;
    }

    /** @throws InvalidTokenException when the account may no longer act as the token says */
    void confirm(AuthenticatedPrincipal principal) {
        AccountStatus status = accounts.status(principal.userId())
                .orElseThrow(() -> new InvalidTokenException(InvalidTokenException.Reason.ACCOUNT_UNKNOWN, null));
        if (!status.active()) {
            throw new InvalidTokenException(InvalidTokenException.Reason.ACCOUNT_BLOCKED, null);
        }
        if (status.role() != principal.role()) {
            throw new InvalidTokenException(InvalidTokenException.Reason.ROLE_CHANGED, null);
        }
    }
}
