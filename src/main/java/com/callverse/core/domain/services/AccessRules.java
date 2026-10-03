package com.callverse.core.domain.services;

import com.callverse.core.domain.exceptions.SelfLockoutException;
import java.util.UUID;

/**
 * Who may change whose access, applied by the adapter on rows it has locked.
 *
 * <ol>
 *   <li><strong>Only an active administrator acts</strong> — creating an account included — and that
 *       is checked again by the adapter under its lock, not only from the token: an administrator
 *       blocked a moment ago cannot finish a request that was already in flight.
 *   <li><strong>No self-lockout</strong> (this class). An administrator never blocks themselves or changes their
 *       own role; another administrator does.
 * </ol>
 *
 * <p><strong>Why an administrator always remains.</strong> Only an active administrator can remove an
 * administrator, never themselves, so after any change the actor is still an active administrator.
 * Two administrators removing each other at the same instant are serialized by the lock, and the
 * second finds they are no longer an administrator and is refused. No separate "last administrator"
 * check is needed: it could never fire.
 */
public final class AccessRules {

    private AccessRules() {
        // Pure functions; never instantiated.
    }

    public static void requireNotSelf(UUID actorId, UUID targetId) {
        if (actorId.equals(targetId)) {
            throw new SelfLockoutException();
        }
    }
}
