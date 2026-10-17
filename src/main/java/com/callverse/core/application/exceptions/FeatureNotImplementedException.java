package com.callverse.core.application.exceptions;

/** A requested application capability is deliberately unavailable. */
public class FeatureNotImplementedException extends RuntimeException {

    public static final String CODE = "NOT_IMPLEMENTED";

    public FeatureNotImplementedException(String message) {
        super(message);
    }
}
