package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.LiveOperations.QueueDepth;
import java.time.Instant;
import java.util.List;

/**
 * The live supervision banner, pushed on {@code /topic/supervision/kpi} and served by
 * {@code GET /api/v1/supervision/kpi}. One shape for both, versioned like {@link SupervisionAlert}.
 *
 * <p>"Today" starts at local midnight in the operations time zone ({@code since}).
 *
 * @param queues waiting conversations per skill, now
 * @param inService conversations an advisor or supervisor holds now: ASSIGNED, ACTIVE, ESCALATED
 * @param averageWaitSeconds mean wait of the conversations assigned today; null when none was
 * @param slaRatio met ÷ measured, today: a conversation is measured when an advisor takes it (met or
 *     missed against its skill's target) or when its customer gives up in the queue after the target
 *     (a miss). Giving up within the target is not measured. Null when nothing was measured
 * @param abandonRate abandoned ÷ (resolved + abandoned) today; null when none ended
 */
public record LiveKpiSnapshot(
        int schemaVersion,
        Instant at,
        Instant since,
        List<QueueDepth> queues,
        long waitingTotal,
        long inService,
        long resolvedToday,
        long abandonedToday,
        Double averageWaitSeconds,
        Double slaRatio,
        Double abandonRate) {

    public static final int SCHEMA_VERSION = 1;

    public LiveKpiSnapshot {
        queues = List.copyOf(queues);
    }
}
