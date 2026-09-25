package com.callverse.core.application.exceptions;

/**
 * A use case needs to know who is calling, and the request carries no verified identity.
 *
 * <p>Distinct from {@link InvalidCredentialsException}, which is a login that failed. This is a
 * call that never presented credentials at all — or presented ones the filter refused, though those
 * are answered by the filter chain before any use case runs. Either way the client's remedy is the
 * same: authenticate, then retry. It maps to <strong>401 {@code UNAUTHENTICATED}</strong>, the same
 * status, code and message the security chain returns, so a client cannot tell which layer refused
 * it and has one path to handle.
 */
public class AuthenticationRequiredException extends ApplicationException {

    private static final String CODE = "UNAUTHENTICATED";

    public AuthenticationRequiredException() {
        super(CODE, "Authentication is required.");
    }
}
