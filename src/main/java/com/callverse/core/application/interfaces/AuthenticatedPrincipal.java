package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.UserRole;
import java.util.Objects;
import java.util.UUID;

/**
 * The caller of the current request, as established by a verified access token.
 *
 * <p>Carries exactly the three claims the token holds, because those are the three facts that are
 * known without a database round trip. Nothing here was re-read from {@code app_user}: a deactivated
 * account keeps producing this principal until its token expires. See
 * {@code JwtAuthenticationFilter} for why that trade was made and how long the window is.
 *
 * <p><strong>{@code customerId} and {@code advisorId} are deliberately absent.</strong> Every
 * ownership rule needs one of them, and the obvious move is to resolve it here. It cannot be done
 * correctly yet. The hop is {@code app_user.id → customer.user_id} (or {@code → advisor.user_id}),
 * and neither column carries a {@code UNIQUE} constraint ({@code V1__init.sql:58} and {@code :130}),
 * so one account may legally own several customer rows and "the customer whose {@code user_id} is
 * mine" has no single answer. {@code docs/user/OWNERSHIP_RULES.md} §0 sets out why the return type
 * of that lookup is undecidable until then, and {@code docs/phases/00_INDEX.md} tracks the missing
 * constraint as <strong>S-1</strong>. When S-1 lands, add the two ids as components of this record;
 * callers that read the existing three accessors are unaffected, which is why this is an object and
 * not a tuple of loose values.
 *
 * @param userId the {@code app_user.id} from the token's {@code sub} claim
 * @param email the account's email at the time the token was issued
 * @param role the account's single role at the time the token was issued
 */
public record AuthenticatedPrincipal(UUID userId, String email, UserRole role) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }

    /**
     * Omits the email. Frameworks print principals in their own debug logging, and an address in a
     * log line is the enumeration leak the login endpoint is careful never to create.
     */
    @Override
    public String toString() {
        return "AuthenticatedPrincipal[userId=" + userId + ", role=" + role + "]";
    }
}
