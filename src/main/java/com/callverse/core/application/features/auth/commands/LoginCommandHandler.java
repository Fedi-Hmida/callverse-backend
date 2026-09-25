package com.callverse.core.application.features.auth.commands;

import com.callverse.core.application.exceptions.InvalidCredentialsException;
import com.callverse.core.application.interfaces.AppUserDirectory;
import com.callverse.core.application.interfaces.IssuedToken;
import com.callverse.core.application.interfaces.PasswordVerifier;
import com.callverse.core.application.interfaces.TokenIssuer;
import com.callverse.core.domain.entities.AppUser;

/**
 * Exchanges an email and a password for an access token.
 *
 * <p>Plain Java: no Spring annotations, no JPA, no jjwt. It is wired by
 * {@code AuthFeatureConfiguration} in the infrastructure layer, the same way
 * {@code GetHealthStatusQueryHandler} is, which is what lets the whole use case be exercised by a
 * unit test with three lambdas and no application context.
 *
 * <p><strong>The single failure mode is deliberate.</strong> Both "no such account" and "wrong
 * password" raise the same {@link InvalidCredentialsException}, with the same code and the same
 * message. Distinguishing them would let an attacker enumerate which addresses hold accounts.
 *
 * <p>Only <em>active</em> accounts are reachable, because {@link AppUserDirectory} exposes no way
 * to load a deactivated one.
 */
public class LoginCommandHandler {

    private final AppUserDirectory directory;
    private final PasswordVerifier passwordVerifier;
    private final TokenIssuer tokenIssuer;

    public LoginCommandHandler(
            AppUserDirectory directory, PasswordVerifier passwordVerifier, TokenIssuer tokenIssuer) {
        this.directory = directory;
        this.passwordVerifier = passwordVerifier;
        this.tokenIssuer = tokenIssuer;
    }

    public LoginResult handle(LoginCommand command) {
        // orElseThrow rather than a branch: an unknown email must not reach the verifier at all.
        // Running a hash comparison against a dummy value would be the usual way to equalise timing,
        // but it is not what protects us here - the identical exception is. See the class javadoc.
        AppUser user =
                directory.findActiveByEmail(command.email()).orElseThrow(InvalidCredentialsException::new);

        if (!passwordVerifier.matches(command.rawPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        IssuedToken token = tokenIssuer.issue(user.getId(), user.getEmail(), user.getRole());
        return new LoginResult(token.value(), token.expiresAt(), user.getRole());
    }
}
