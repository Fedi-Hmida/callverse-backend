package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.UserRole;
import java.util.UUID;

/**
 * Mints an access token for an authenticated account.
 *
 * <p>The signature takes the three claims the token must carry rather than an {@code AppUser},
 * so that the implementation cannot quietly start serialising the whole entity — including the
 * password hash — into a token that is handed to a browser.
 *
 * <p>No JWT type appears here. The adapter chooses the encoding; {@code core} only knows that some
 * string identifies the caller afterwards.
 */
public interface TokenIssuer {

    /**
     * @param subject the {@code app_user.id} the token identifies
     * @param email the account's email, carried for the client's convenience
     * @param role the account's single role
     */
    IssuedToken issue(UUID subject, String email, UserRole role);
}
