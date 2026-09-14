package com.callverse.core.domain.exceptions;

/**
 * Base type for conditions the business forbids.
 *
 * <p>A {@code DomainException} means a rule of the relation center was violated: a commercial
 * credit beyond the ceiling, a conversation routed to an advisor without the required skill, a
 * ticket transitioned to a state it cannot reach from where it is. It does not mean the request was
 * malformed or the caller was unauthorised; those are application and host concerns respectively.
 *
 * <p>Unchecked on purpose. These conditions are not recoverable at the call site: the caller cannot
 * meaningfully catch "the credit ceiling was exceeded" and try something else, so forcing every
 * intermediate method to declare it would add noise without adding a decision.
 *
 * <p>Each subclass carries a stable {@link #code()}. The code, not the message, is what clients
 * branch on: messages are for humans and will be reworded, and in this product some of them will
 * eventually be translated into French for the customer portal.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Stable machine-readable identifier, for example {@code CREDIT_CEILING_EXCEEDED}. */
    public String code() {
        return code;
    }
}
