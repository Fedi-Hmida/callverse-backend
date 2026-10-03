package com.callverse.core.application.interfaces;

/**
 * Turns a password into the hash stored in {@code app_user.password_hash}. The counterpart of
 * {@link PasswordVerifier}, and a port for the same reason: Spring Security's encoder may not enter
 * {@code core}.
 */
public interface PasswordHasher {

    /** @param rawPassword never logged, never stored */
    String hash(String rawPassword);
}
