package com.callverse.infrastructure.config;

import com.callverse.core.application.features.conversation.queries.GetSupervisionAlertsQueryHandler;
import com.callverse.core.application.interfaces.SupervisionAlertHistory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupervisionHistoryConfiguration {
    @Bean GetSupervisionAlertsQueryHandler getSupervisionAlertsQueryHandler(SupervisionAlertHistory history) {
        return new GetSupervisionAlertsQueryHandler(history);
    }
}
