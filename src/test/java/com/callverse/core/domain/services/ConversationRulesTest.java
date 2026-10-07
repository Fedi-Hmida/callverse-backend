package com.callverse.core.domain.services;

import static com.callverse.core.domain.enums.ConversationStatus.ABANDONED;
import static com.callverse.core.domain.enums.ConversationStatus.ACTIVE;
import static com.callverse.core.domain.enums.ConversationStatus.ASSIGNED;
import static com.callverse.core.domain.enums.ConversationStatus.ESCALATED;
import static com.callverse.core.domain.enums.ConversationStatus.QUEUED;
import static com.callverse.core.domain.enums.ConversationStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.exceptions.AdvisorUnavailableException;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import com.callverse.core.domain.services.ConversationRules.Assignment;
import com.callverse.core.domain.services.ConversationRules.Closure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The lifecycle rules and the measurements written at each transition: plain unit tests, no Spring,
 * no database. Every illegal edge is walked, and the SLA boundary is tested at, just under and just
 * over the target.
 */
class ConversationRulesTest {

    private static final Instant QUEUED_AT = Instant.parse("2026-10-02T07:00:00Z");

    @Nested
    @DisplayName("assignment: QUEUED -> ASSIGNED")
    class Assign {

        @Test
        @DisplayName("records the whole seconds waited and meets the SLA at exactly the target")
        void atTarget() {
            Assignment a = ConversationRules.assign(QUEUED, QUEUED_AT, QUEUED_AT.plusSeconds(60), 60);
            assertThat(a.waitSeconds()).isEqualTo(60);
            assertThat(a.slaMet()).isTrue();
        }

        @Test
        @DisplayName("misses the SLA one second over the target")
        void overTarget() {
            Assignment a = ConversationRules.assign(QUEUED, QUEUED_AT, QUEUED_AT.plusSeconds(61), 60);
            assertThat(a.waitSeconds()).isEqualTo(61);
            assertThat(a.slaMet()).isFalse();
        }

        @Test
        @DisplayName("truncates to whole seconds: 30.9 s against a 30 s target is 30 and met")
        void truncates() {
            Assignment a = ConversationRules.assign(QUEUED, QUEUED_AT, QUEUED_AT.plusMillis(30_900), 30);
            assertThat(a.waitSeconds()).isEqualTo(30);
            assertThat(a.slaMet()).isTrue();
        }

        @Test
        @DisplayName("a clock that moved backwards never yields a negative wait")
        void clockSkew() {
            Assignment a = ConversationRules.assign(QUEUED, QUEUED_AT, QUEUED_AT.minusSeconds(5), 60);
            assertThat(a.waitSeconds()).isZero();
            assertThat(a.slaMet()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = ConversationStatus.class, names = "QUEUED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("is refused from every state but QUEUED")
        void onlyFromQueued(ConversationStatus from) {
            assertThatThrownBy(() -> ConversationRules.assign(from, QUEUED_AT, QUEUED_AT, 60))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("engagement: ASSIGNED -> ACTIVE")
    class Engage {

        @Test
        @DisplayName("an ASSIGNED conversation becomes ACTIVE; an ACTIVE or ESCALATED one is already engaged")
        void engages() {
            assertThat(ConversationRules.statusAfterAdvisorMessage(ASSIGNED)).isEqualTo(ACTIVE);
            assertThat(ConversationRules.statusAfterAdvisorMessage(ACTIVE)).isEqualTo(ACTIVE);
            assertThat(ConversationRules.statusAfterAdvisorMessage(ESCALATED)).isEqualTo(ESCALATED);
        }

        @ParameterizedTest
        @EnumSource(value = ConversationStatus.class, names = {"QUEUED", "RESOLVED", "ABANDONED"})
        @DisplayName("an advisor cannot write in a queued or closed conversation")
        void refused(ConversationStatus from) {
            assertThatThrownBy(() -> ConversationRules.statusAfterAdvisorMessage(from))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("a customer may write while waiting or being served, never once it is closed")
        void customerMessages() {
            assertThat(ConversationRules.acceptsCustomerMessage(QUEUED)).isTrue();
            assertThat(ConversationRules.acceptsCustomerMessage(ASSIGNED)).isTrue();
            assertThat(ConversationRules.acceptsCustomerMessage(ACTIVE)).isTrue();
            assertThat(ConversationRules.acceptsCustomerMessage(ESCALATED)).isTrue();
            assertThat(ConversationRules.acceptsCustomerMessage(RESOLVED)).isFalse();
            assertThat(ConversationRules.acceptsCustomerMessage(ABANDONED)).isFalse();
        }
    }

    @Nested
    @DisplayName("closure: -> RESOLVED or ABANDONED")
    class Close {

        @Test
        @DisplayName("resolving records the handle time from assignment to the end")
        void resolve() {
            Instant assigned = QUEUED_AT.plusSeconds(42);
            Closure c = ConversationRules.close(ACTIVE, RESOLVED, QUEUED_AT, assigned, assigned.plusSeconds(360));
            assertThat(c.handleSeconds()).isEqualTo(360);
            assertThat(c.waitSeconds()).isNull();
        }

        @Test
        @DisplayName("abandoning in the queue records how long the customer waited, and no handle time")
        void abandonInQueue() {
            Closure c = ConversationRules.close(QUEUED, ABANDONED, QUEUED_AT, null, QUEUED_AT.plusSeconds(95));
            assertThat(c.waitSeconds()).isEqualTo(95);
            assertThat(c.handleSeconds()).isNull();
        }

        @Test
        @DisplayName("abandoning after assignment records the handle time so far")
        void abandonServed() {
            Instant assigned = QUEUED_AT.plusSeconds(10);
            Closure c = ConversationRules.close(ASSIGNED, ABANDONED, QUEUED_AT, assigned, assigned.plusSeconds(20));
            assertThat(c.handleSeconds()).isEqualTo(20);
            assertThat(c.waitSeconds()).isNull();
        }

        @Test
        @DisplayName("an escalated conversation is resolved, never abandoned: the state machine says so")
        void escalated() {
            assertThat(ConversationRules.close(ESCALATED, RESOLVED, QUEUED_AT, QUEUED_AT, QUEUED_AT.plusSeconds(1))
                    .handleSeconds()).isEqualTo(1);
            assertThatThrownBy(() -> ConversationRules.close(ESCALATED, ABANDONED, QUEUED_AT, QUEUED_AT, QUEUED_AT))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @ParameterizedTest
        @EnumSource(value = ConversationStatus.class, names = {"QUEUED", "ASSIGNED", "RESOLVED", "ABANDONED"})
        @DisplayName("resolving is refused from every state but ACTIVE and ESCALATED")
        void resolveRefused(ConversationStatus from) {
            assertThatThrownBy(() -> ConversationRules.close(from, RESOLVED, QUEUED_AT, QUEUED_AT, QUEUED_AT))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @ParameterizedTest
        @EnumSource(value = ConversationStatus.class, names = {"ESCALATED", "RESOLVED", "ABANDONED"})
        @DisplayName("abandoning is refused once escalated or closed")
        void abandonRefused(ConversationStatus from) {
            assertThatThrownBy(() -> ConversationRules.close(from, ABANDONED, QUEUED_AT, QUEUED_AT, QUEUED_AT))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @ParameterizedTest
        @EnumSource(value = ConversationStatus.class, names = {"QUEUED", "ASSIGNED", "ACTIVE", "ESCALATED"})
        @DisplayName("close only ends a conversation: any other target is a programming error")
        void onlyTerminalTargets(ConversationStatus target) {
            assertThatThrownBy(() -> ConversationRules.close(ACTIVE, target, QUEUED_AT, QUEUED_AT, QUEUED_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("SLA when the customer leaves the queue")
    class LeftQueue {

        @Test
        @DisplayName("giving up after the target is a miss; giving up within it is not counted either way")
        void abandonment() {
            assertThat(ConversationRules.slaMetWhenLeavingQueue(61, 60)).isFalse();
            assertThat(ConversationRules.slaMetWhenLeavingQueue(60, 60)).isNull();
            assertThat(ConversationRules.slaMetWhenLeavingQueue(5, 60)).isNull();
        }
    }

    @Nested
    @DisplayName("capacity")
    class Capacity {

        @Test
        @DisplayName("an advisor below max_concurrent may take work; at it, ADVISOR_UNAVAILABLE")
        void capacity() {
            UUID advisor = UUID.randomUUID();
            ConversationRules.requireCapacity(advisor, 0, 1);
            ConversationRules.requireCapacity(advisor, 1, 2);
            assertThatThrownBy(() -> ConversationRules.requireCapacity(advisor, 1, 1))
                    .isInstanceOf(AdvisorUnavailableException.class)
                    .extracting("code").isEqualTo("ADVISOR_UNAVAILABLE");
            assertThatThrownBy(() -> ConversationRules.requireCapacity(advisor, 3, 2))
                    .isInstanceOf(AdvisorUnavailableException.class);
        }
    }
}
