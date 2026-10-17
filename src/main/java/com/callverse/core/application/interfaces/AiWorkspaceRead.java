package com.callverse.core.application.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Safe read models for the supervision AI workspace. Never exposes agent traces. */
public interface AiWorkspaceRead {
    Options options();
    List<UUID> recentRunIds();
    List<QualityView> quality(UUID conversationId);

    record ScenarioOption(UUID id, String name, String loadProfile) {}
    record StrategyOption(String code, String name) {}
    record Options(List<ScenarioOption> scenarios, List<StrategyOption> strategies) {}
    record QualityView(UUID id, BigDecimal globalScore, Map<String, Integer> scores,
                       String explanation, String evaluator, Instant createdAt) {}
}
