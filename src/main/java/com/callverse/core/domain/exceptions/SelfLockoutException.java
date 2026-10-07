package com.callverse.core.domain.exceptions;

/**
 * An administrator tried to block themselves or change their own role. Refused so that nobody locks
 * themselves out by mistake: another administrator does it, or nobody does.
 */
public class SelfLockoutException extends DomainException {

    private static final String CODE = "SELF_LOCKOUT";

    public SelfLockoutException() {
        super(CODE, "An administrator cannot block themselves or change their own role.");
    }
}
