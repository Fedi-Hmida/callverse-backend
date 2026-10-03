package com.callverse.core.domain.services;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * What a password set by an administrator must satisfy. Length over composition, as NIST SP 800-63B
 * recommends: forced symbol rules produce "Password1!", a long phrase does not.
 *
 * <ul>
 *   <li>At least 12 characters.
 *   <li>At most 72 bytes in UTF-8: BCrypt ignores everything after the 72nd byte, so a longer
 *       password would quietly be a shorter one.
 *   <li>Not one character repeated, and not containing the name part of the account's own address.
 * </ul>
 *
 * <p>The reason returned never contains the password.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
        // Pure function; never instantiated.
    }

    /** @return why the password is refused, or empty when it is acceptable */
    public static Optional<String> violation(String password, String email) {
        if (password == null || password.isBlank()) {
            return Optional.of("password is required");
        }
        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return Optional.of("password must be at least %d characters".formatted(MIN_LENGTH));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("password must be at most %d bytes".formatted(MAX_BYTES));
        }
        if (password.chars().distinct().count() == 1) {
            return Optional.of("password must not repeat a single character");
        }
        String name = email == null ? "" : email.split("@", 2)[0].toLowerCase(Locale.ROOT);
        if (name.length() >= 3 && password.toLowerCase(Locale.ROOT).contains(name)) {
            return Optional.of("password must not contain the account's email name");
        }
        return Optional.empty();
    }
}
