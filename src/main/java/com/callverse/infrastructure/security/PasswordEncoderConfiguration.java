package com.callverse.infrastructure.security;

import com.callverse.core.application.interfaces.PasswordHasher;
import com.callverse.core.application.interfaces.PasswordVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing.
 *
 * <p><strong>A bare {@link BCryptPasswordEncoder}, deliberately not
 * {@code PasswordEncoderFactories.createDelegatingPasswordEncoder()}.</strong> The delegating
 * encoder expects every stored hash to carry an algorithm prefix such as <code>{bcrypt}</code>, and
 * the four accounts seeded by {@code V2__seed_reference.sql} carry none. Wiring the delegating
 * encoder would reject all four with <em>"There is no PasswordEncoder mapped for the id null"</em>
 * — a failure that reads like a broken password rather than a broken configuration.
 * {@code SeedPasswordHashTest} pins this: it verifies the seeded hashes with a bare encoder, so
 * swapping this bean for a delegating one turns that test red rather than breaking the demo.
 *
 * <p>The cost factor is BCrypt's default of 10, which is what the seeded hashes were generated at.
 * Raising it is safe for new hashes and irrelevant to existing ones — BCrypt stores its cost inside
 * the hash — but it would slow every login, so it is left alone.
 */
@Configuration
public class PasswordEncoderConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Adapts Spring Security's encoder to the {@link PasswordVerifier} port.
     *
     * <p>The port exists because {@code org.springframework.security..} is forbidden inside
     * {@code core} by an ArchUnit rule, so the use case cannot take a {@code PasswordEncoder}
     * directly.
     */
    @Bean
    PasswordVerifier passwordVerifier(PasswordEncoder encoder) {
        return encoder::matches;
    }

    /** Adapts the same encoder to the {@link PasswordHasher} port, for accounts an administrator creates. */
    @Bean
    PasswordHasher passwordHasher(PasswordEncoder encoder) {
        return encoder::encode;
    }
}
