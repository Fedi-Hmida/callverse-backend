package com.callverse.infrastructure.config;

import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.features.workforce.commands.RequestWorkforceRecommendationCommandHandler;
import com.callverse.core.application.features.workforce.commands.ApplyWorkforceRecommendationCommandHandler;
import com.callverse.core.application.features.workforce.queries.GetWorkforceCandidatesQueryHandler;
import com.callverse.core.application.interfaces.WorkforceAdministration;
import com.callverse.core.application.interfaces.AccessRevocation;
import com.callverse.core.application.interfaces.LiveOperations;
import com.callverse.core.application.interfaces.WorkforceAgent;
import com.callverse.core.application.interfaces.WorkforceDecisionLog;
import com.callverse.core.application.interfaces.WorkforceOperations;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires human-reviewed Workforce recommendations from live backend data. */
@Configuration
public class WorkforceFeatureConfiguration {

    @Bean GetWorkforceCandidatesQueryHandler getWorkforceCandidatesQueryHandler(WorkforceAdministration workforce) {
        return new GetWorkforceCandidatesQueryHandler(workforce);
    }

    @Bean ApplyWorkforceRecommendationCommandHandler applyWorkforceRecommendationCommandHandler(
            WorkforceAdministration workforce,AccessRevocation revocation) {
        return new ApplyWorkforceRecommendationCommandHandler(workforce,revocation);
    }

    @Bean
    RequestWorkforceRecommendationCommandHandler requestWorkforceRecommendationCommandHandler(
            WorkforceOperations workforceOperations,
            LiveOperations liveOperations,
            ConversationEvents events,
            WorkforceAgent agent,
            WorkforceDecisionLog decisionLog,
            Clock clock,
            @Value("${callverse.operations.zone:Europe/Paris}") ZoneId operationsZone) {
        return new RequestWorkforceRecommendationCommandHandler(
                workforceOperations, liveOperations, events, agent, decisionLog, clock, operationsZone);
    }
}
