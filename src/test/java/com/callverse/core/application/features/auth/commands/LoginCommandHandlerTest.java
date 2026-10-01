package com.callverse.core.application.features.auth.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.InvalidCredentialsException;
import com.callverse.core.application.interfaces.AppUserDirectory;
import com.callverse.core.application.interfaces.IssuedToken;
import com.callverse.core.application.interfaces.PasswordVerifier;
import com.callverse.core.application.interfaces.TokenIssuer;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.enums.UserRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The login use case, tested in isolation from Spring, JPA and jjwt.
 *
 * <p>The three collaborators are ports declared in {@code core.application.interfaces}, so this
 * test needs no container and no context: it substitutes plain hand-written fakes. That is the
 * property the layering exists to buy, and it is why the handler takes a {@code PasswordVerifier}
 * rather than Spring Security's {@code PasswordEncoder} — the latter lives under
 * {@code org.springframework.security..}, which the ArchUnit rules forbid {@code core} to import.
 *
 * <p><strong>The anti-enumeration guarantee is the reason this test exists.</strong> An unknown
 * email and a valid email with the wrong password must be indistinguishable to the caller. If they
 * differ by exception type, by code, or by message, the endpoint becomes an oracle that confirms
 * which addresses hold accounts.
 */
class LoginCommandHandlerTest {

    private static final String EMAIL = "advisor@callverse.local";
    private static final String RAW_PASSWORD = "Admin111***";
    private static final String STORED_HASH = "$2a$10$storedhashvaluethatthefakeverifierwillcompareagainst00";
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    @DisplayName("valid credentials yield a token carrying the account's id and role")
    void validCredentialsIssueAToken() {
        Instant expiry = Instant.parse("2026-09-25T12:00:00Z");
        LoginCommandHandler handler =
                new LoginCommandHandler(
                        directoryContaining(activeUser()),
                        (raw, hash) -> raw.equals(RAW_PASSWORD) && hash.equals(STORED_HASH),
                        (subject, email, role) -> {
                            assertThat(subject).isEqualTo(USER_ID);
                            assertThat(email).isEqualTo(EMAIL);
                            assertThat(role).isEqualTo(UserRole.ADVISOR);
                            return new IssuedToken("a.jwt.value", expiry);
                        });

        LoginResult result = handler.handle(new LoginCommand(EMAIL, RAW_PASSWORD));

        assertThat(result.token()).isEqualTo("a.jwt.value");
        assertThat(result.expiresAt()).isEqualTo(expiry);
        assertThat(result.role()).isEqualTo(UserRole.ADVISOR);
    }

    @Test
    @DisplayName("an unknown email is refused without consulting the password verifier")
    void unknownEmailIsRefused() {
        LoginCommandHandler handler =
                new LoginCommandHandler(
                        email -> Optional.empty(),
                        (raw, hash) -> {
                            throw new AssertionError(
                                    "the verifier must not run for an unknown email - doing so leaks"
                                            + " timing information about which addresses exist");
                        },
                        failingIssuer());

        assertThatThrownBy(() -> handler.handle(new LoginCommand("nobody@callverse.local", RAW_PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("a wrong password is refused")
    void wrongPasswordIsRefused() {
        LoginCommandHandler handler =
                new LoginCommandHandler(
                        directoryContaining(activeUser()), (raw, hash) -> false, failingIssuer());

        assertThatThrownBy(() -> handler.handle(new LoginCommand(EMAIL, "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("unknown email and wrong password are indistinguishable to the caller")
    void thetwoFailuresAreIndistinguishable() {
        InvalidCredentialsException fromUnknownEmail =
                captureFailure(
                        new LoginCommandHandler(
                                email -> Optional.empty(), (raw, hash) -> false, failingIssuer()),
                        new LoginCommand("nobody@callverse.local", RAW_PASSWORD));

        InvalidCredentialsException fromWrongPassword =
                captureFailure(
                        new LoginCommandHandler(
                                directoryContaining(activeUser()), (raw, hash) -> false, failingIssuer()),
                        new LoginCommand(EMAIL, "wrong"));

        assertThat(fromWrongPassword.code())
                .as("a differing code would tell an attacker which emails exist")
                .isEqualTo(fromUnknownEmail.code());
        assertThat(fromWrongPassword.getMessage())
                .as("a differing message would tell an attacker which emails exist")
                .isEqualTo(fromUnknownEmail.getMessage());
    }

    private static InvalidCredentialsException captureFailure(
            LoginCommandHandler handler, LoginCommand command) {
        try {
            handler.handle(command);
        } catch (InvalidCredentialsException expected) {
            return expected;
        }
        throw new AssertionError("expected the login to be refused");
    }

    private static AppUser activeUser() {
        AppUser user = new AppUser();
        // app_user.id is @Setter(AccessLevel.NONE): Hibernate generates it, so nothing in the
        // application may assign one. Reflection here rather than a test-only setter on the
        // entity - production code must not grow an accessor that exists only for tests.
        setId(user, USER_ID);
        user.setEmail(EMAIL);
        user.setPasswordHash(STORED_HASH);
        user.setFirstName("Test");
        user.setLastName("Advisor");
        user.setRole(UserRole.ADVISOR);
        user.setActive(true);
        return user;
    }

    private static void setId(AppUser user, UUID id) {
        try {
            java.lang.reflect.Field field = AppUser.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AppUser.id is no longer a field named 'id'", e);
        }
    }

    private static AppUserDirectory directoryContaining(AppUser user) {
        return email -> email.equalsIgnoreCase(user.getEmail()) ? Optional.of(user) : Optional.empty();
    }

    private static TokenIssuer failingIssuer() {
        return (subject, email, role) -> {
            throw new AssertionError("no token may be issued for a refused login");
        };
    }
}
