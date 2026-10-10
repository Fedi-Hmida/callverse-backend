package com.callverse.infrastructure.config;

import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.features.conversation.commands.CloseConversationCommandHandler;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommandHandler;
import com.callverse.core.application.features.conversation.commands.PostMessageCommandHandler;
import com.callverse.core.application.features.conversation.commands.TakeNextConversationCommandHandler;
import com.callverse.core.application.features.conversation.queries.ConversationSubscriptionPolicy;
import com.callverse.core.application.features.conversation.queries.GetConversationQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetHeldConversationsQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetLiveKpiQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetQueuesQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetTranscriptQueryHandler;
import com.callverse.core.application.features.conversation.queries.SearchConversationsQueryHandler;
import com.callverse.core.application.interfaces.AdvisorDirectory;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationSupervision;
import com.callverse.core.application.interfaces.LiveOperations;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the conversation core: the life of a call from the queue to its end, and its live events.
 *
 * <p>{@code callverse.operations.zone} decides when "today" starts for the live KPIs: local
 * midnight where the contact centre works. Timestamps themselves stay UTC everywhere.
 */
@Configuration
public class ConversationFeatureConfiguration {

    @Bean
    ConversationEvents conversationEvents(
            RealtimeEventPublisher publisher,
            LiveOperations operations,
            Clock clock,
            @Value("${callverse.operations.zone:Europe/Paris}") ZoneId zone) {
        return new ConversationEvents(publisher, operations, clock, zone);
    }

    @Bean
    OpenConversationCommandHandler openConversationCommandHandler(
            ConversationLifecycle conversations, ConversationEvents events, Clock clock) {
        return new OpenConversationCommandHandler(conversations, events, clock);
    }

    @Bean
    TakeNextConversationCommandHandler takeNextConversationCommandHandler(
            AdvisorDirectory advisors, ConversationLifecycle conversations, ConversationEvents events, Clock clock) {
        return new TakeNextConversationCommandHandler(advisors, conversations, events, clock);
    }

    @Bean
    PostMessageCommandHandler postMessageCommandHandler(
            ConversationLifecycle conversations, ConversationMessages messages, ConversationEvents events, Clock clock) {
        return new PostMessageCommandHandler(conversations, messages, events, clock);
    }

    @Bean
    CloseConversationCommandHandler closeConversationCommandHandler(
            ConversationLifecycle conversations, ConversationEvents events, Clock clock) {
        return new CloseConversationCommandHandler(conversations, events, clock);
    }

    @Bean
    GetConversationQueryHandler getConversationQueryHandler(ConversationLifecycle conversations) {
        return new GetConversationQueryHandler(conversations);
    }

    @Bean
    GetTranscriptQueryHandler getTranscriptQueryHandler(
            ConversationLifecycle conversations, ConversationMessages messages) {
        return new GetTranscriptQueryHandler(conversations, messages);
    }

    @Bean
    GetHeldConversationsQueryHandler getHeldConversationsQueryHandler(
            AdvisorDirectory advisors, ConversationLifecycle conversations) {
        return new GetHeldConversationsQueryHandler(advisors, conversations);
    }

    @Bean
    GetQueuesQueryHandler getQueuesQueryHandler(AdvisorDirectory advisors, LiveOperations operations, Clock clock) {
        return new GetQueuesQueryHandler(advisors, operations, clock);
    }

    @Bean
    GetLiveKpiQueryHandler getLiveKpiQueryHandler(LiveOperations operations, ConversationEvents events, Clock clock) {
        return new GetLiveKpiQueryHandler(operations, events, clock);
    }

    @Bean
    SearchConversationsQueryHandler searchConversationsQueryHandler(ConversationSupervision supervision) {
        return new SearchConversationsQueryHandler(supervision);
    }

    @Bean
    ConversationSubscriptionPolicy conversationSubscriptionPolicy(
            ConversationLifecycle conversations, AdvisorDirectory advisors) {
        return new ConversationSubscriptionPolicy(conversations, advisors);
    }
}
