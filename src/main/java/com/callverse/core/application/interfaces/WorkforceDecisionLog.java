package com.callverse.core.application.interfaces;

import java.time.Instant;

/** Persists the exact observation and human-review-only proposal returned by the Workforce agent. */
public interface WorkforceDecisionLog {

    long record(WorkforceAgent.WorkforceState observation, WorkforceAgent.WorkforceProposal proposal, Instant at);
}
