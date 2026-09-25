package com.callverse.infrastructure.config;

import com.callverse.core.application.features.auth.commands.LoginCommandHandler;
import com.callverse.core.application.interfaces.AppUserDirectory;
import com.callverse.core.application.interfaces.PasswordVerifier;
import com.callverse.core.application.interfaces.TokenIssuer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the auth feature slice.
 *
 * <p>Mirrors {@code HealthFeatureConfiguration}: the handler itself carries no Spring annotation,
 * so the composition happens here rather than by component scanning. That is what keeps
 * {@code core} free of the framework and lets the use case be unit-tested with three lambdas.
 */
@Configuration
public class AuthFeatureConfiguration {

    @Bean
    LoginCommandHandler loginCommandHandler(
            AppUserDirectory directory, PasswordVerifier passwordVerifier, TokenIssuer tokenIssuer) {
        return new LoginCommandHandler(directory, passwordVerifier, tokenIssuer);
    }
}
