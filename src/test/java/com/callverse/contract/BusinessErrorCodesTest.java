package com.callverse.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.exceptions.SlaPolicyNotFoundException;
import com.callverse.core.domain.enums.AdvisorStatus;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.exceptions.AdvisorUnavailableException;
import com.callverse.core.domain.exceptions.ConversationAlreadyAssignedException;
import com.callverse.core.domain.exceptions.CreditLimitExceededException;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import com.callverse.core.domain.exceptions.RunAlreadyExistsException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the six business error codes that the inter-repository API contract mandates.
 *
 * <p><strong>Why a test for constants.</strong> These strings are a contract with two other
 * repositories: the Next.js client and the Python AI service both branch on {@code code}, never on
 * {@code message}. A rename here is a silent breaking change there — it compiles, it passes review,
 * and the other repository's error handling stops matching. This test makes a rename a failing
 * build, which is the only enforcement a cross-repository string constant can have.
 *
 * <p><strong>The codes are quoted from the contract, not invented.</strong> One earlier document in
 * this project wrote {@code CREDIT_CEILING_EXCEEDED}; the contract says
 * {@code CREDIT_LIMIT_EXCEEDED}. That is exactly the class of drift this test exists to stop.
 *
 * <p>No database, no Spring context: these are constructors and a getter.
 */
class BusinessErrorCodesTest {

    @Test
    @DisplayName("CREDIT_LIMIT_EXCEEDED — the ceiling the backend, not the agent, enforces")
    void creditLimitExceeded() {
        CreditLimitExceededException e =
                new CreditLimitExceededException(new BigDecimal("75.00"), new BigDecimal("50.00"));

        assertThat(e.code()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
        assertThat(e.getMessage()).contains("75.00").contains("50.00");
    }

    @Test
    @DisplayName("CONVERSATION_ALREADY_ASSIGNED")
    void conversationAlreadyAssigned() {
        assertThat(new ConversationAlreadyAssignedException(UUID.randomUUID(), UUID.randomUUID()).code())
                .isEqualTo("CONVERSATION_ALREADY_ASSIGNED");
    }

    @Test
    @DisplayName("ADVISOR_UNAVAILABLE")
    void advisorUnavailable() {
        AdvisorUnavailableException e =
                new AdvisorUnavailableException(UUID.randomUUID(), AdvisorStatus.OFFLINE);

        assertThat(e.code()).isEqualTo("ADVISOR_UNAVAILABLE");
        assertThat(e.getMessage()).contains("OFFLINE");
    }

    @Test
    @DisplayName("RUN_ALREADY_EXISTS — the uq_run guarantee, surfaced as a clean refusal")
    void runAlreadyExists() {
        RunAlreadyExistsException e =
                new RunAlreadyExistsException(UUID.randomUUID(), UUID.randomUUID(), 42L);

        assertThat(e.code()).isEqualTo("RUN_ALREADY_EXISTS");
        assertThat(e.getMessage()).contains("42");
    }

    @Test
    @DisplayName("ADVISOR_PROFILE_NOT_FOUND — a 404 with its own code, telling an administrator what to fix")
    void advisorProfileNotFound() {
        com.callverse.core.application.exceptions.AdvisorProfileNotFoundException e =
                new com.callverse.core.application.exceptions.AdvisorProfileNotFoundException(UUID.randomUUID());
        assertThat(e.code()).isEqualTo("ADVISOR_PROFILE_NOT_FOUND");
        assertThat(e).isInstanceOf(com.callverse.core.application.exceptions.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("ADVISOR_UNAVAILABLE at capacity names the count, so the refusal is actionable")
    void advisorAtCapacity() {
        AdvisorUnavailableException e = AdvisorUnavailableException.atCapacity(UUID.randomUUID(), 2, 2);
        assertThat(e.code()).isEqualTo("ADVISOR_UNAVAILABLE");
        assertThat(e.getMessage()).contains("2 of 2");
    }

    @Test
    @DisplayName("SLA_POLICY_NOT_FOUND — its own code, not the generic RESOURCE_NOT_FOUND")
    void slaPolicyNotFound() {
        SlaPolicyNotFoundException e = new SlaPolicyNotFoundException(UUID.randomUUID());

        assertThat(e.code())
                .as("the contract names this code specifically; RESOURCE_NOT_FOUND would not match")
                .isEqualTo("SLA_POLICY_NOT_FOUND");

        // The 404 lives in GlobalExceptionHandler's ResourceNotFoundException handler; a bare
        // ApplicationException maps to 400. Asserting the type is how this test pins the status
        // without standing up an endpoint that throws it.
        assertThat(e)
                .as("must inherit the 404 mapping, not ApplicationException's 400")
                .isInstanceOf(com.callverse.core.application.exceptions.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("INVALID_STATE_TRANSITION — already shipped, pinned here with the other five")
    void invalidStateTransition() {
        assertThat(
                        new InvalidStateTransitionException(
                                        ConversationStatus.RESOLVED, ConversationStatus.ACTIVE)
                                .code())
                .isEqualTo("INVALID_STATE_TRANSITION");
    }
}
