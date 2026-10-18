package com.callverse.infrastructure.config;

import com.callverse.core.application.features.simulation.commands.RunDigitalTwinCommandHandler;
import com.callverse.core.application.features.simulation.queries.GetDigitalTwinRunsQueryHandler;
import com.callverse.core.application.interfaces.TwinRunStore;
import com.callverse.core.application.interfaces.TwinSimulator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the digital twin runs: scenario × strategy × seed, KPIs in run_kpi. */
@Configuration
public class DigitalTwinConfiguration {

    @Bean
    RunDigitalTwinCommandHandler runDigitalTwinCommandHandler(TwinRunStore store, TwinSimulator simulator, Clock clock) {
        return new RunDigitalTwinCommandHandler(store, simulator, clock);
    }

    @Bean
    GetDigitalTwinRunsQueryHandler getDigitalTwinRunsQueryHandler(TwinRunStore store) {
        return new GetDigitalTwinRunsQueryHandler(store);
    }
}
