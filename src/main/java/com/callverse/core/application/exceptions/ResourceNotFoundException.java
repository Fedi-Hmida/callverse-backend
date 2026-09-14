package com.callverse.core.application.exceptions;

/**
 * A use case referenced something that does not exist: a customer, a conversation, a simulation
 * run, a ticket.
 *
 * <p>The message is deliberately assembled from a resource type and an identifier rather than
 * accepting free text, so that every 404 this system produces reads the same way and so that no
 * caller accidentally puts customer data into an error message that will be logged.
 */
public class ResourceNotFoundException extends ApplicationException {

    private static final String CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String resourceType, Object identifier) {
        super(CODE, "%s '%s' was not found".formatted(resourceType, identifier));
    }
}
