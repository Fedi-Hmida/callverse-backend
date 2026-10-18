package com.callverse.infrastructure.config;

import com.callverse.core.application.features.simulation.commands.RunConversationSimulationCommandHandler;
import com.callverse.core.application.features.simulation.commands.StartConversationSimulationCommandHandler;
import com.callverse.core.application.features.simulation.queries.GetConversationSimulationQueryHandler;
import com.callverse.core.application.interfaces.AdvisorAgent;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.SimulationRunScheduler;
import com.callverse.core.application.interfaces.SimulatorAgent;
import com.callverse.infrastructure.ai.SimulationAgentRetry;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Wires isolated end-to-end Client Simulator and Advisor runs. */
@Configuration
public class ConversationSimulationConfiguration {

    @Bean
    StartConversationSimulationCommandHandler startConversationSimulationCommandHandler(
            ConversationSimulationStore store, SimulationRunScheduler scheduler, Clock clock) {
        return new StartConversationSimulationCommandHandler(store, scheduler, clock);
    }

    @Bean
    GetConversationSimulationQueryHandler getConversationSimulationQueryHandler(ConversationSimulationStore store) {
        return new GetConversationSimulationQueryHandler(store);
    }

    @Bean
    RunConversationSimulationCommandHandler runConversationSimulationCommandHandler(
            SimulatorAgent simulator, AdvisorAgent advisor, ConversationSimulationStore store, Clock clock) {
        return new RunConversationSimulationCommandHandler(
                request -> SimulationAgentRetry.call(() -> simulator.nextMessage(request)),
                request -> SimulationAgentRetry.call(() -> advisor.respond(request)), store, clock);
    }

    @Bean(name = "conversationSimulationExecutor")
    TaskExecutor conversationSimulationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("conversation-simulation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
