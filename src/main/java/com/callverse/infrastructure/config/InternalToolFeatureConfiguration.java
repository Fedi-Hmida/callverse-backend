package com.callverse.infrastructure.config;

import com.callverse.core.application.features.customer.queries.GetCustomerProfileQueryHandler;
import com.callverse.core.application.features.customer.queries.GetRecentInvoicesQueryHandler;
import com.callverse.core.application.interfaces.CustomerRecords;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use cases behind the {@code /internal} agent tools.
 *
 * <p>Same shape as {@code HealthFeatureConfiguration}: the handlers are plain classes in
 * {@code core}, and this is the one place they become beans.
 */
@Configuration
public class InternalToolFeatureConfiguration {

    @Bean
    GetCustomerProfileQueryHandler getCustomerProfileQueryHandler(CustomerRecords customers) {
        return new GetCustomerProfileQueryHandler(customers);
    }

    @Bean
    GetRecentInvoicesQueryHandler getRecentInvoicesQueryHandler(CustomerRecords customers) {
        return new GetRecentInvoicesQueryHandler(customers);
    }
}
