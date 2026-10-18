package com.callverse.infrastructure.config;

import com.callverse.core.application.features.admin.commands.ChangeUserAccessCommandHandler;
import com.callverse.core.application.features.admin.commands.CreateUserCommandHandler;
import com.callverse.core.application.features.admin.commands.ConfigureAdvisorCommandHandler;
import com.callverse.core.application.features.admin.queries.GetAdvisorProfileQueryHandler;
import com.callverse.core.application.interfaces.AdvisorAdministration;
import com.callverse.core.application.features.admin.queries.GetUserQueryHandler;
import com.callverse.core.application.features.admin.queries.ListUsersQueryHandler;
import com.callverse.core.application.interfaces.AccessRevocation;
import com.callverse.core.application.interfaces.PasswordHasher;
import com.callverse.core.application.interfaces.UserAccounts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires account administration: giving, changing and withdrawing access. */
@Configuration
public class AdminFeatureConfiguration {

    @Bean
    ConfigureAdvisorCommandHandler configureAdvisorCommandHandler(AdvisorAdministration advisors, AccessRevocation revocation) {
        return new ConfigureAdvisorCommandHandler(advisors, revocation);
    }

    @Bean
    GetAdvisorProfileQueryHandler getAdvisorProfileQueryHandler(AdvisorAdministration advisors) {
        return new GetAdvisorProfileQueryHandler(advisors);
    }

    @Bean
    CreateUserCommandHandler createUserCommandHandler(UserAccounts accounts, PasswordHasher hasher) {
        return new CreateUserCommandHandler(accounts, hasher);
    }

    @Bean
    ChangeUserAccessCommandHandler changeUserAccessCommandHandler(UserAccounts accounts, AccessRevocation revocation) {
        return new ChangeUserAccessCommandHandler(accounts, revocation);
    }

    @Bean
    ListUsersQueryHandler listUsersQueryHandler(UserAccounts accounts) {
        return new ListUsersQueryHandler(accounts);
    }

    @Bean
    GetUserQueryHandler getUserQueryHandler(UserAccounts accounts) {
        return new GetUserQueryHandler(accounts);
    }
}
