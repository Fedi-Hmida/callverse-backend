package com.callverse.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Proves that the four BCrypt hashes seeded by {@code V2__seed_reference.sql} actually verify
 * against the development password documented in the README.
 *
 * <p><strong>Why this test exists.</strong> The hashes were committed before any code could check
 * them, and nothing verified them since. A mismatch would not surface until the day the login
 * endpoint ships, and it would present as "login is broken" with an invisible cause — the hashes
 * look perfectly well-formed either way. This turns a silent, deferred failure into a failing
 * build.
 *
 * <p><strong>It reads the migration rather than hard-coding the hashes.</strong> A copy here would
 * drift: someone re-seeding with a different password would leave this test passing against values
 * the database no longer contains. Parsing the real file means the test fails the moment the seed
 * and the documented password disagree, which is the only failure worth catching.
 *
 * <p><strong>It uses a bare {@link BCryptPasswordEncoder} deliberately.</strong> Not
 * {@code PasswordEncoderFactories.createDelegatingPasswordEncoder()}, which expects a
 * {@code {bcrypt}} prefix and rejects every one of these hashes with "There is no PasswordEncoder
 * mapped for the id null". The production bean must make the same choice, and this test is the
 * first place that constraint is expressed in code rather than prose.
 *
 * <p>No database and no Docker: this is pure hashing arithmetic over a file on disk.
 */
class SeedPasswordHashTest {

    /**
     * The development password, documented in README.md. Public knowledge by design — these four
     * accounts exist for local development and the demo, and the plaintext lives in the README
     * rather than in a SQL comment, because a password in a comment is a password in the schema.
     */
    private static final String DEV_PASSWORD = "Admin111***";

    /**
     * Where the effective hashes live. V2 seeded the accounts; V4 rotated their password. V2 cannot
     * be edited (it is applied on Neon), so the hashes that log a user in today are V4's.
     */
    private static final Path SEED_MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "V4__rotate_dev_passwords.sql");

    /** Matches a BCrypt hash in the seed file: the 2a variant, cost, then the 53-char payload. */
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");

    @Test
    @DisplayName("every seeded password hash verifies against the documented development password")
    void seededHashesVerify() throws IOException {
        List<String> hashes = seededHashes();

        assertThat(hashes)
                .as("BCrypt hashes found in %s", SEED_MIGRATION)
                .hasSize(4);

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        for (String hash : hashes) {
            assertThat(encoder.matches(DEV_PASSWORD, hash))
                    .as("hash %s should verify against the documented development password", hash)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a wrong password does not verify, so the check above is not vacuous")
    void wrongPasswordDoesNotVerify() throws IOException {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        assertThat(encoder.matches("not-the-password", seededHashes().get(0)))
                .as("a wrong password must not verify — otherwise the positive test proves nothing")
                .isFalse();
    }

    private static List<String> seededHashes() throws IOException {
        assertThat(Files.exists(SEED_MIGRATION)).as("%s is missing", SEED_MIGRATION).isTrue();
        String sql = Files.readString(SEED_MIGRATION);
        Matcher matcher = BCRYPT.matcher(sql);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
