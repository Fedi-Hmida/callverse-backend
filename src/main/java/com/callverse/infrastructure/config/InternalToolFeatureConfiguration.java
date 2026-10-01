package com.callverse.infrastructure.config;

import com.callverse.core.application.features.conversation.commands.EscalateConversationCommandHandler;
import com.callverse.core.application.features.incident.queries.GetServiceStatusQueryHandler;
import com.callverse.core.application.features.knowledge.queries.SearchKnowledgeBaseQueryHandler;
import com.callverse.core.application.features.ticket.commands.OpenTicketCommandHandler;
import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.application.interfaces.KnowledgeBase;
import com.callverse.core.application.interfaces.ServiceIncidents;
import com.callverse.core.application.interfaces.Tickets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use cases that were behind the {@code /internal} agent tools.
 *
 * <p><strong>Parked.</strong> The {@code /internal} HTTP surface was withdrawn on 2026-09-30
 * pending the AI-integration phase; the git tag {@code internal-tools-http-surface} holds it. These
 * beans have no HTTP caller until the use cases are re-exposed under {@code /api/v1} for the
 * frontend, which is when each moves to its own feature slice's configuration.
 *
 * <p>Same shape as {@code HealthFeatureConfiguration}: the handlers are plain classes in
 * {@code core}, and this is the one place they become beans.
 */
@Configuration
public class InternalToolFeatureConfiguration {

    @Bean
    GetServiceStatusQueryHandler getServiceStatusQueryHandler(ServiceIncidents incidents) {
        return new GetServiceStatusQueryHandler(incidents);
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
