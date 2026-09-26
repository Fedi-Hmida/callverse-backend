package com.callverse.infrastructure.config;

import com.callverse.core.application.features.conversation.commands.EscalateConversationCommandHandler;
import com.callverse.core.application.features.knowledge.queries.SearchKnowledgeBaseQueryHandler;
import com.callverse.core.application.features.network.queries.GetNetworkStatusQueryHandler;
import com.callverse.core.application.features.ticket.commands.OpenTicketCommandHandler;
import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.application.interfaces.KnowledgeBase;
import com.callverse.core.application.interfaces.NetworkIncidents;
import com.callverse.core.application.interfaces.Tickets;
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
    GetNetworkStatusQueryHandler getNetworkStatusQueryHandler(NetworkIncidents incidents) {
        return new GetNetworkStatusQueryHandler(incidents);
    }

    @Bean
    SearchKnowledgeBaseQueryHandler searchKnowledgeBaseQueryHandler(KnowledgeBase knowledgeBase) {
        return new SearchKnowledgeBaseQueryHandler(knowledgeBase);
    }

    @Bean
    OpenTicketCommandHandler openTicketCommandHandler(
            CustomerRecords customers, ConversationDirectory conversations, Tickets tickets) {
        return new OpenTicketCommandHandler(customers, conversations, tickets);
    }

    @Bean
    EscalateConversationCommandHandler escalateConversationCommandHandler(
            ConversationDirectory conversations, Escalations escalations) {
        return new EscalateConversationCommandHandler(conversations, escalations);
    }
}
