package com.callverse.core.application.exceptions;

/** The Workforce service could not return a valid recommendation. */
public class WorkforceServiceUnavailableException extends ApplicationException {

    public static final String CODE = "WORKFORCE_UNAVAILABLE";

    public WorkforceServiceUnavailableException(String message, Throwable cause) {
        super(CODE, message, cause);
    }

    public WorkforceServiceUnavailableException(String message) {
        super(CODE, message);
    }
}
