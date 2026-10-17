package com.callverse.core.application.features.simulation.commands;

import com.callverse.core.application.interfaces.AiWorkspaceRead.ScenarioOption;
import com.callverse.core.application.interfaces.DialogueScenarioStore;

/** Creates the named reference of a dialogue, not a load-simulation campaign. */
public class CreateDialogueScenarioCommandHandler {
    private final DialogueScenarioStore scenarios;
    public CreateDialogueScenarioCommandHandler(DialogueScenarioStore scenarios) { this.scenarios = scenarios; }
    public ScenarioOption handle(String name) { return scenarios.createDialogueScenario(name.strip()); }
}
