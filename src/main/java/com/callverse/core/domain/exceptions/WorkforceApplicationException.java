package com.callverse.core.domain.exceptions;

public class WorkforceApplicationException extends DomainException {
    public WorkforceApplicationException(String message) { super("WORKFORCE_APPLICATION_CONFLICT",message); }
}
