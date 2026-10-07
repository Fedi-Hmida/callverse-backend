package com.callverse.core.application.interfaces;

/**
 * Checks a submitted password against a stored hash.
 *
 * <p><strong>Why this is not Spring Security's {@code PasswordEncoder}.</strong> That type lives
 * under {@code org.springframework.security..}, which the ArchUnit rule
 * {@code core_must_not_depend_on_spring} forbids {@code core} to import. This port keeps the use
 * case testable with a two-line lambda and keeps the choice of hashing algorithm where it belongs,
 * in the adapter.
 *
 * <p>Only verification is exposed. Hashing a new password is a different concern and belongs to
 * user provisioning, which is sub-phase 2.9 and not yet built.
 */
@FunctionalInterface
public interface PasswordVerifier {

    /**
     * @param rawPassword the password as submitted, never logged
     * @param storedHash the hash held in {@code app_user.password_hash}
     * @return whether the password produces the stored hash
     */
    boolean matches(String rawPassword, String storedHash);
}
