package com.callverse.infrastructure.config;

import com.callverse.core.application.features.quality.queries.AiWorkspaceQueryHandler;
import com.callverse.core.application.interfaces.AiWorkspaceRead;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.DialogueScenarioStore;
import com.callverse.core.application.features.simulation.commands.CreateDialogueScenarioCommandHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiWorkspaceConfiguration {
    @Bean
    CreateDialogueScenarioCommandHandler createDialogueScenarioCommandHandler(DialogueScenarioStore scenarios) {
        return new CreateDialogueScenarioCommandHandler(scenarios);
    }

    @Bean
    AiWorkspaceQueryHandler aiWorkspaceQueryHandler(AiWorkspaceRead read,
            ConversationLifecycle conversations, ConversationSimulationStore simulations) {
        return new AiWorkspaceQueryHandler(read, conversations, simulations);
    }
}
