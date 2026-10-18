package com.callverse.infrastructure.config;

import com.callverse.core.application.features.auth.commands.LoginCommandHandler;
import com.callverse.core.application.features.auth.queries.GetCurrentUserQueryHandler;
import com.callverse.core.application.interfaces.AppUserDirectory;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.PasswordVerifier;
import com.callverse.core.application.interfaces.TokenIssuer;
import com.callverse.core.application.interfaces.AuthSessions;
import com.callverse.core.application.interfaces.AccessRevocation;
import com.callverse.core.application.interfaces.CustomerSelfService;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.PasswordHasher;
import com.callverse.core.application.features.auth.commands.RegisterCustomerCommandHandler;
import com.callverse.core.application.features.auth.commands.SessionTokensHandler;
import com.callverse.core.application.features.customer.CustomerPortalHandler;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommandHandler;
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
    RegisterCustomerCommandHandler registerCustomerCommandHandler(CustomerSelfService customers,PasswordHasher hasher) {
        return new RegisterCustomerCommandHandler(customers,hasher);
    }
    @Bean
    SessionTokensHandler sessionTokensHandler(AuthSessions sessions,TokenIssuer tokens,AccessRevocation live) {
        return new SessionTokensHandler(sessions,tokens,live);
    }
    @Bean
    CustomerPortalHandler customerPortalHandler(CurrentPrincipalProvider principals,CustomerSelfService customers,
                                                CustomerRecords records,OpenConversationCommandHandler conversations) {
        return new CustomerPortalHandler(principals,customers,records,conversations);
    }

    @Bean
    LoginCommandHandler loginCommandHandler(
            AppUserDirectory directory, PasswordVerifier passwordVerifier, TokenIssuer tokenIssuer) {
        return new LoginCommandHandler(directory, passwordVerifier, tokenIssuer);
    }

    @Bean
    GetCurrentUserQueryHandler getCurrentUserQueryHandler(CurrentPrincipalProvider principals) {
        return new GetCurrentUserQueryHandler(principals);
    }
}
