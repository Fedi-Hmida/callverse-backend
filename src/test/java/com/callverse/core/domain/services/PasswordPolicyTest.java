package com.callverse.core.domain.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The temporary password an administrator sets. Length over composition (NIST SP 800-63B): at least
 * 12 characters, at most 72 bytes because BCrypt silently ignores the rest, and not the account's
 * own address.
 */
class PasswordPolicyTest {

    @Test
    @DisplayName("12 characters is enough; 11 is not")
    void length() {
        assertThat(PasswordPolicy.violation("correct-hor", "a@b.c")).isPresent();
        assertThat(PasswordPolicy.violation("correct-hors", "a@b.c")).isEmpty();
    }

    @Test
    @DisplayName("more than 72 bytes is refused: BCrypt would silently ignore the rest")
    void bcryptLimit() {
        assertThat(PasswordPolicy.violation("xy".repeat(36), "a@b.c")).as("72 bytes").isEmpty();
        assertThat(PasswordPolicy.violation("xy".repeat(36) + "z", "a@b.c")).as("73 bytes").isPresent();
        assertThat(PasswordPolicy.violation("éa".repeat(25), "a@b.c"))
                .as("50 characters but 75 bytes in UTF-8").hasValue("password must be at most 72 bytes");
    }

    @Test
    @DisplayName("a password containing the address's name is refused, whatever its case")
    void notTheEmail() {
        assertThat(PasswordPolicy.violation("Karim.Benali-2026!", "karim.benali@bank.fr")).isPresent();
        assertThat(PasswordPolicy.violation("totally-unrelated-1", "karim.benali@bank.fr")).isEmpty();
    }

    @Test
    @DisplayName("one character repeated is refused")
    void monotone() {
        assertThat(PasswordPolicy.violation("aaaaaaaaaaaaaa", "a@b.c")).isPresent();
    }

    @Test
    @DisplayName("null or blank is refused")
    void blank() {
        assertThat(PasswordPolicy.violation(null, "a@b.c")).isPresent();
        assertThat(PasswordPolicy.violation("            ", "a@b.c")).isPresent();
    }

    @Test
    @DisplayName("the reason never contains the password")
    void reasonDoesNotEcho() {
        assertThat(PasswordPolicy.violation("short-pw", "a@b.c")).hasValueSatisfying(
                reason -> assertThat(reason).doesNotContain("short-pw"));
    }
}
