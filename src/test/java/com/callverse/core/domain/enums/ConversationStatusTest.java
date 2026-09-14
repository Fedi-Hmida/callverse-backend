package com.callverse.core.domain.enums;

import static com.callverse.core.domain.enums.ConversationStatus.ABANDONED;
import static com.callverse.core.domain.enums.ConversationStatus.ACTIVE;
import static com.callverse.core.domain.enums.ConversationStatus.ASSIGNED;
import static com.callverse.core.domain.enums.ConversationStatus.ESCALATED;
import static com.callverse.core.domain.enums.ConversationStatus.QUEUED;
import static com.callverse.core.domain.enums.ConversationStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the conversation state machine.
 *
 * <p>Worth testing despite being "just an enum": this is the only written-down copy of the legal
 * transitions, no use case enforces it yet, and an unguarded rule with no current caller is exactly
 * the kind that drifts before its first consumer arrives. These are plain unit tests — no Spring
 * context, no database.
 */
class ConversationStatusTest {

    @ParameterizedTest
    @EnumSource(ConversationStatus.class)
    @DisplayName("every constant has a transition set, so allowedNext never throws")
    void everyConstantIsMapped(ConversationStatus status) {
        // Regression guard for the static initialiser: a constant added to the enum but forgotten
        // in the TRANSITIONS map would return null here and NPE at the first caller instead.
        assertThat(status.allowedNext()).isNotNull();
    }

    @Test
    @DisplayName("the happy path QUEUED -> ASSIGNED -> ACTIVE -> RESOLVED is legal end to end")
    void happyPathIsLegal() {
        assertThat(QUEUED.canTransitionTo(ASSIGNED)).isTrue();
        assertThat(ASSIGNED.canTransitionTo(ACTIVE)).isTrue();
        assertThat(ACTIVE.canTransitionTo(RESOLVED)).isTrue();
    }

    @Test
    @DisplayName("a conversation can be abandoned from any non-terminal state except ESCALATED")
    void abandonmentRules() {
        assertThat(QUEUED.canTransitionTo(ABANDONED)).isTrue();
        assertThat(ASSIGNED.canTransitionTo(ABANDONED)).isTrue();
        assertThat(ACTIVE.canTransitionTo(ABANDONED)).isTrue();
        // Deliberate: once a supervisor owns it, it is seen through rather than dropped.
        assertThat(ESCALATED.canTransitionTo(ABANDONED)).isFalse();
    }

    @Test
    @DisplayName("escalation is reachable only from ACTIVE and leads only to RESOLVED")
    void escalationRules() {
        assertThat(ACTIVE.canTransitionTo(ESCALATED)).isTrue();
        assertThat(QUEUED.canTransitionTo(ESCALATED)).isFalse();
        assertThat(ASSIGNED.canTransitionTo(ESCALATED)).isFalse();
        assertThat(ESCALATED.allowedNext()).containsExactly(RESOLVED);
    }

    @Test
    @DisplayName("terminal states admit nothing further")
    void terminalStatesAreTerminal() {
        assertThat(RESOLVED.isTerminal()).isTrue();
        assertThat(ABANDONED.isTerminal()).isTrue();
        assertThat(RESOLVED.canTransitionTo(QUEUED)).isFalse();
        assertThat(ABANDONED.canTransitionTo(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("a null target and a self-transition are both rejected")
    void nullAndSelfTransitionsRejected() {
        assertThat(ACTIVE.canTransitionTo(null)).isFalse();
        // Re-entering the state you are already in is a bug at the call site, not a no-op.
        assertThat(ACTIVE.canTransitionTo(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("allowedNext is unmodifiable, so a caller cannot edit the rule at runtime")
    void allowedNextIsUnmodifiable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ACTIVE.allowedNext().add(QUEUED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
