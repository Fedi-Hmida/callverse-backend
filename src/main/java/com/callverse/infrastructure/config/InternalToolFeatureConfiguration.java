package com.callverse.infrastructure.config;

import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.features.conversation.commands.EscalateConversationCommandHandler;
import com.callverse.core.application.features.incident.queries.GetServiceStatusQueryHandler;
import com.callverse.core.application.features.knowledge.queries.SearchKnowledgeBaseQueryHandler;
import com.callverse.core.application.features.ticket.commands.OpenTicketCommandHandler;
import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.application.interfaces.KnowledgeBase;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.ServiceIncidents;
import com.callverse.core.application.interfaces.Tickets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use cases that were behind the {@code /internal} agent tools, and that the advisor
 * workspace routes now call.
 *
 * <p>The {@code /internal} HTTP surface was withdrawn on 2026-09-30 pending the AI-integration
 * phase (git tag {@code internal-tools-http-surface}). Since the advisor-workspace phase these beans
 * serve {@code /api/v1} routes; the AI phase will call the same beans again.
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
            ConversationDirectory conversations,
            Escalations escalations,
            RealtimeEventPublisher events,
            ConversationEvents conversationEvents) {
        return new EscalateConversationCommandHandler(conversations, escalations, events, conversationEvents);
    }
}
