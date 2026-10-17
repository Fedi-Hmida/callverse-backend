package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.AiWorkspaceRead;
import com.callverse.core.application.interfaces.DialogueScenarioStore;
import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.enums.LoadProfile;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.callverse.infrastructure.persistence.repositories.ControlStrategyRepository;
import com.callverse.infrastructure.persistence.repositories.QualityEvaluationRepository;
import com.callverse.infrastructure.persistence.repositories.ScenarioRepository;
import com.callverse.infrastructure.persistence.repositories.SimulationRunRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AiWorkspaceDataAdapter implements AiWorkspaceRead, DialogueScenarioStore {
    private final ScenarioRepository scenarios;
    private final ControlStrategyRepository strategies;
    private final SimulationRunRepository runs;
    private final QualityEvaluationRepository evaluations;

    @Override
    public Options options() {
        return new Options(
                scenarios.findAll(Sort.by("name")).stream()
                        .map(s -> new ScenarioOption(s.getId(), s.getName(), s.getLoadProfile().name())).toList(),
                strategies.findAll(Sort.by("name")).stream()
                        .map(s -> new StrategyOption(s.getCode(), s.getName())).toList());
    }

    @Override
    @Transactional
    public ScenarioOption createDialogueScenario(String name) {
        Scenario scenario = new Scenario();
        scenario.setName(name);
        scenario.setLoadProfile(LoadProfile.LOW);
        scenario.setDurationMinutes(1);
        scenario.setAdvisorCount(1);
        scenario.setSkillDistribution(JsonNodeFactory.instance.objectNode());
        scenario.setCustomerProfileMix(JsonNodeFactory.instance.objectNode());
        scenarios.saveAndFlush(scenario);
        return new ScenarioOption(scenario.getId(), scenario.getName(), scenario.getLoadProfile().name());
    }

    @Override
    public List<UUID> recentRunIds() {
        return runs.findAll(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "startedAt")))
                .stream().map(r -> r.getId()).toList();
    }

    @Override
    public List<QualityView> quality(UUID conversationId) {
        return evaluations.findByConversationId(conversationId).stream()
                .sorted(Comparator.comparing(e -> e.getCreatedAt(), Comparator.reverseOrder()))
                .map(e -> {
                    var scores = new LinkedHashMap<String, Integer>();
                    e.getScores().fields().forEachRemaining(field -> {
                        if (field.getValue().isIntegralNumber()) scores.put(field.getKey(), field.getValue().intValue());
                    });
                    return new QualityView(e.getId(), e.getGlobalScore(), scores, e.getExplanation(),
                            e.getEvaluator().name(), e.getCreatedAt());
                }).toList();
    }
}
