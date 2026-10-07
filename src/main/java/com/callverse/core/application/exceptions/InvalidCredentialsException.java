package com.callverse.core.application.exceptions;

/**
 * Authentication was refused.
 *
 * <p><strong>Deliberately carries no detail about why.</strong> An unknown email and a valid email
 * with the wrong password must produce an identical code and an identical message, because any
 * difference between them turns the login endpoint into an oracle: an attacker submits an address,
 * reads which failure came back, and learns whether an account exists. That is the whole reason
 * this exception takes no arguments.
 *
 * <p>It maps to <strong>401</strong> rather than 400. The request was well-formed; the credentials
 * were not accepted. Note this is an MVC-level failure thrown by the use case and handled by
 * {@code GlobalExceptionHandler}; denials raised by the security filter chain never reach that
 * handler and are written, in the same envelope, by the chain's own entry point.
 */
public class InvalidCredentialsException extends ApplicationException {

    private static final String CODE = "INVALID_CREDENTIALS";

    public InvalidCredentialsException() {
        super(CODE, "Invalid email or password.");
    }
}
