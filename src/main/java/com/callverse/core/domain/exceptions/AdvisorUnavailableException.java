package com.callverse.core.domain.exceptions;

import com.callverse.core.domain.enums.AdvisorStatus;
import java.util.UUID;

/**
 * An advisor was selected for work they cannot currently take.
 *
 * <p>A 409: the advisor exists and the caller is entitled to ask, but the advisor's present state
 * forbids the assignment. The message names the status because that is the only detail that makes
 * the refusal actionable — a supervisor reading it needs to know whether the advisor is offline, on
 * a break, or already at capacity.
 *
 * <p>Named in the inter-repository API contract. Thrown by routing in Phase 4, and by the manual
 * reassignment the supervisor screen offers.
 */
public class AdvisorUnavailableException extends DomainException {

    private static final String CODE = "ADVISOR_UNAVAILABLE";

    public AdvisorUnavailableException(UUID advisorId, AdvisorStatus status) {
        super(CODE, "Advisor %s cannot take work while %s.".formatted(advisorId, status));
    }

    private AdvisorUnavailableException(String message) {
        super(CODE, message);
    }

    /** The advisor already holds as many conversations as {@code advisor.max_concurrent} allows. */
    public static AdvisorUnavailableException atCapacity(UUID advisorId, long held, int maxConcurrent) {
        return new AdvisorUnavailableException(
                "Advisor %s is at capacity: %d of %d conversations.".formatted(advisorId, held, maxConcurrent));
    }
}
