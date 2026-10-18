package com.callverse.core.application.exceptions;

/** The Quality Analyst is unavailable or returned an unusable evaluation. */
public class QualityServiceUnavailableException extends RuntimeException {

    public QualityServiceUnavailableException(String message) {
        super(message);
    }

    public QualityServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
