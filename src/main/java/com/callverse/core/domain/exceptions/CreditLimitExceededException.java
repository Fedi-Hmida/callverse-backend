package com.callverse.core.domain.exceptions;

import java.math.BigDecimal;

/**
 * A commercial gesture exceeded the granting advisor's rolling ceiling.
 *
 * <p><strong>The code is {@code CREDIT_LIMIT_EXCEEDED}, and the spelling matters.</strong> It is
 * named in the inter-repository API contract and again in the AI lot's own brief, and the Python
 * agent branches on it: over the ceiling it must escalate to a human rather than retry. An earlier
 * document in this project wrote {@code CREDIT_CEILING_EXCEEDED}; that would have left the agent
 * with an unrecognised code and no escalation path.
 *
 * <p><strong>Why this is a domain exception and therefore a 409.</strong> The request was
 * well-formed and the caller did nothing wrong syntactically — the current state of the business
 * forbids it. A 400 would tell the agent to fix its input, which is exactly the wrong instruction.
 *
 * <p>Thrown by the credit tool in Phase 3, which is blocked on an open decision: when the agent
 * handles a conversation alone there is no advisor row and therefore no ceiling to compare against.
 * Declared here now so the contract's vocabulary exists before the code that needs it.
 */
public class CreditLimitExceededException extends DomainException {

    private static final String CODE = "CREDIT_LIMIT_EXCEEDED";

    /**
     * @param requested the amount the caller asked to grant
     * @param limit the ceiling it exceeded — the advisor's own, not a global constant
     */
    public CreditLimitExceededException(BigDecimal requested, BigDecimal limit) {
        super(
                CODE,
                "A credit of %s exceeds the ceiling of %s; the request must be escalated."
                        .formatted(requested, limit));
    }
}
