package com.callverse.infrastructure.config;

import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.features.conversation.commands.AdvisorTurnCommandHandler;
import com.callverse.core.application.interfaces.AdvisorAgent;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.domain.enums.AiAdvisorMode;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Wires the asynchronous live-conversation Advisor integration. */
@Configuration
public class AdvisorFeatureConfiguration {

    @Bean
    AdvisorTurnCommandHandler advisorTurnCommandHandler(
            AdvisorAgent agent,
            ConversationLifecycle conversations,
            ConversationMessages messages,
            CustomerRecords customers,
            ConversationEvents events,
            Clock clock,
            @Value("${ai.advisor.mode:OFF}") AiAdvisorMode mode) {
        if (mode == null) {
            // An empty AI_ADVISOR_MODE binds to null; name the setting instead of a bare NPE.
            throw new IllegalStateException("ai.advisor.mode (AI_ADVISOR_MODE) must be OFF or AUTONOMOUS");
        }
        return new AdvisorTurnCommandHandler(agent, conversations, messages, customers, events, clock, mode);
    }

    @Bean(name = "advisorTurnExecutor")
    TaskExecutor advisorTurnExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("advisor-turn-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
