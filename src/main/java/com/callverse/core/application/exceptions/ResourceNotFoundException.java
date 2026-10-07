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

    /**
     * For a subclass that is also a 404 but carries its own contract code.
     *
     * <p>The 404 status is decided once, by {@code GlobalExceptionHandler}'s handler for this type;
     * a bare {@link ApplicationException} maps to 400. Extending this class is therefore how a more
     * specific not-found exception inherits the right status without a second handler repeating the
     * mapping — and without being forced to emit {@code RESOURCE_NOT_FOUND} when the API contract
     * names something narrower.
     */
    protected ResourceNotFoundException(String code, String message) {
        super(code, message);
    }
}
