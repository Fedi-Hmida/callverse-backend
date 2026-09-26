package com.callverse.core.domain.exceptions;

import java.util.UUID;

/**
 * A simulation run was launched for a triple that already exists.
 *
 * <p><strong>This exception is how the experiment's control surfaces to a caller.</strong> The
 * database enforces {@code uq_run (scenario_id, strategy_id, seed)} because at an identical seed the
 * arrival flow must be identical across strategies — that is what makes a strategy-versus-strategy
 * delta mean anything at all. Recording the same triple twice would quietly corrupt the comparison.
 *
 * <p>A 409, and deliberately raised <em>before</em> the insert rather than caught after it. The
 * repository already offers an existence check for precisely this reason: letting the constraint
 * violation propagate would surface as a 500 with a stack trace, telling the caller the server
 * broke when in fact the caller asked for something the protocol forbids.
 *
 * <p>Named in the inter-repository API contract. Thrown by the run lifecycle in Phase 5.
 */
public class RunAlreadyExistsException extends DomainException {

    private static final String CODE = "RUN_ALREADY_EXISTS";

    public RunAlreadyExistsException(UUID scenarioId, UUID strategyId, long seed) {
        super(
                CODE,
                "A run already exists for scenario %s, strategy %s, seed %d."
                        .formatted(scenarioId, strategyId, seed));
    }
}
