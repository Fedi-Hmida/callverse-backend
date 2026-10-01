package com.callverse.core.application.exceptions;

/**
 * No customer carries the reference the caller typed. A 404 like any other, with a fixed message:
 * the reference is caller input, and an error that echoes input back is an error that echoes it
 * into every log and screen that displays it.
 */
public class CustomerReferenceNotFoundException extends ResourceNotFoundException {

    private static final String CODE = "RESOURCE_NOT_FOUND";

    public CustomerReferenceNotFoundException() {
        super(CODE, "No customer has this reference.");
    }
}
