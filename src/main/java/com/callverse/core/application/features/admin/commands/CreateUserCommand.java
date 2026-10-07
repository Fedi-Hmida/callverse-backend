package com.callverse.core.application.features.admin.commands;

import com.callverse.core.domain.enums.UserRole;
import java.util.UUID;

/**
 * An administrator gives someone access.
 *
 * @param actorId the administrator creating the account, re-checked under the lock
 * @param password the temporary password the administrator sets; checked against
 *     {@code PasswordPolicy}, hashed, and never stored, logged or returned
 */
public record CreateUserCommand(
        UUID actorId, String email, String firstName, String lastName, UserRole role, String password) {

    /** Never the password: records print their fields, and this one must not reach a log. */
    @Override
    public String toString() {
        return "CreateUserCommand[actorId=" + actorId + ", email=" + email + ", role=" + role + "]";
    }
}
