package com.callverse.core.application.interfaces;

/** Creates reference scenarios for isolated single-dialogue runs. */
public interface DialogueScenarioStore {
    AiWorkspaceRead.ScenarioOption createDialogueScenario(String name);
}
