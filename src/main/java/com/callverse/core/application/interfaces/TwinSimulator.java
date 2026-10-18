package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.LoadProfile;
import java.util.Objects;

/**
 * Port to the digital twin: replays one simulated contact-centre day under a control strategy.
 * Deterministic: the same specification always yields the same KPIs.
 */
public interface TwinSimulator {

    TwinKpis run(TwinRunSpec spec);

    /** @param strategy the AI service's strategy name: STATIC_FIFO, THRESHOLD or PPO */
    record TwinRunSpec(String strategy, long seed, LoadProfile load, int durationMinutes, int advisorsPerSkill) {

        public TwinRunSpec {
            Objects.requireNonNull(strategy, "strategy must not be null");
            Objects.requireNonNull(load, "load must not be null");
        }
    }

    /**
     * @param slaAllArrivals the reference SLA: answered in time over all arrivals, abandons included
     * @param slaRate answered in time over answered only, kept for comparison with older reports
     */
    record TwinKpis(
            int arrived,
            int served,
            double slaRate,
            double slaAllArrivals,
            double abandonRate,
            double avgWaitSeconds,
            double p95WaitSeconds,
            int reassignments) {}
}
