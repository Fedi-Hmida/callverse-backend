package com.callverse.infrastructure.config;

import com.callverse.core.application.features.customer.queries.GetCustomerProfileQueryHandler;
import com.callverse.core.application.features.customer.queries.GetRecentInvoicesQueryHandler;
import com.callverse.core.application.interfaces.CustomerRecords;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the customer feature slice.
 *
 * <p>Copies {@code HealthFeatureConfiguration}'s shape, as its javadoc instructs: the handlers carry
 * no Spring annotation and are constructed here, one {@code @Bean} each.
 *
 * <p><strong>These two beans moved out of {@code InternalToolFeatureConfiguration}.</strong> They
 * were declared there when the AI tool API was their only consumer. It now has a second one — the
 * staff-facing {@code CustomerController} — and a shared bean filed under one of its two consumers
 * is the kind of thing that reads as accidental six months later. The slice owns them; the surfaces
 * that use them do not.
 */
@Configuration
public class CustomerFeatureConfiguration {

    @Bean
    GetCustomerProfileQueryHandler getCustomerProfileQueryHandler(CustomerRecords customers) {
        return new GetCustomerProfileQueryHandler(customers);
    }

    @Bean
    GetRecentInvoicesQueryHandler getRecentInvoicesQueryHandler(CustomerRecords customers) {
        return new GetRecentInvoicesQueryHandler(customers);
    }
}
