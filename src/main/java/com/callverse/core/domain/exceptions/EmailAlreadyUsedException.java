package com.callverse.core.domain.exceptions;

/**
 * An account with this address already exists, active or blocked. A 409: the request is well formed,
 * the existing account forbids it. The message does not repeat the address.
 *
 * <p>Only an administrator can reach the route that raises it, so telling them the address is taken
 * is not the enumeration leak it would be on a public sign-up form.
 */
public class EmailAlreadyUsedException extends DomainException {

    private static final String CODE = "EMAIL_ALREADY_USED";

    public EmailAlreadyUsedException() {
        super(CODE, "An account with this email address already exists.");
    }
}
