package com.callverse.core.application.exceptions;

/**
 * Base type for failures of a use case that are not violations of a business rule.
 *
 * <p>The distinction from {@code DomainException} is worth keeping sharp, because the two map to
 * different HTTP statuses and blurring them produces an API where clients cannot tell "you asked
 * for something that does not exist" from "what you asked for is forbidden by the business".
 *
 * <ul>
 *   <li>{@code DomainException}: the operation is well-formed but the business refuses it.
 *   <li>{@code ApplicationException}: the operation could not be carried out at all, because a
 *       referenced thing is absent or a precondition of the use case is unmet.
 * </ul>
 */
public abstract class ApplicationException extends RuntimeException {

    private final String code;

    protected ApplicationException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected ApplicationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Stable machine-readable identifier, surfaced as {@code code} in the API error envelope. */
    public String code() {
        return code;
    }
}
