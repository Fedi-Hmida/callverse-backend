package com.callverse.core.domain.exceptions;

/** A reference edit would overwrite a newer version or break an existing business relationship. */
public class ReferenceDataConflictException extends DomainException {
    public ReferenceDataConflictException(String message) {
        super("REFERENCE_DATA_CONFLICT", message);
    }
}
