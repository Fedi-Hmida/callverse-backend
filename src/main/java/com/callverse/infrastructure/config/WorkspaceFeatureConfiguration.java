package com.callverse.infrastructure.config;

import com.callverse.core.application.features.card.commands.BlockCardCommandHandler;
import com.callverse.core.application.interfaces.Cards;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the card slice of the advisor workspace. Same shape as {@code HealthFeatureConfiguration}:
 * the handler is a plain class in {@code core}, and this is the one place it becomes a bean.
 */
@Configuration
public class WorkspaceFeatureConfiguration {

    @Bean
    BlockCardCommandHandler blockCardCommandHandler(Cards cards, Clock clock) {
        return new BlockCardCommandHandler(cards, clock);
    }
}
