package com.callverse.core.application.exceptions;

/**
 * A request parameter is outside what the use case accepts — for example asking for 500
 * transactions when the use case returns at most 50.
 *
 * <p>Carries the same {@code VALIDATION_FAILED} code as Bean Validation at the edge, because to the
 * caller the remedy is the same: fix the input. The bounds themselves live in the handlers, which
 * are the authority on what a use case accepts.
 */
public class InvalidRequestException extends ApplicationException {

    private static final String CODE = "VALIDATION_FAILED";

    public InvalidRequestException(String message) {
        super(CODE, message);
    }
}
