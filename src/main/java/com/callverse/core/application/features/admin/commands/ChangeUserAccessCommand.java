package com.callverse.core.application.features.admin.commands;

import com.callverse.core.domain.enums.UserRole;
import java.util.UUID;

/**
 * An administrator changes what an account may do: its role, or whether it may act at all.
 * Exactly one of {@code role} and {@code active} is set; the route decides which.
 *
 * @param actorId the administrator acting, re-checked under the lock
 */
public record ChangeUserAccessCommand(UUID actorId, UUID targetId, UserRole role, Boolean active) {

    public static ChangeUserAccessCommand role(UUID actorId, UUID targetId, UserRole role) {
        return new ChangeUserAccessCommand(actorId, targetId, role, null);
    }

    public static ChangeUserAccessCommand active(UUID actorId, UUID targetId, boolean active) {
        return new ChangeUserAccessCommand(actorId, targetId, null, active);
    }
}
