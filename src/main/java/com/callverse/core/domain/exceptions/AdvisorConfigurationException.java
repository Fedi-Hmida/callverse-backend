package com.callverse.core.domain.exceptions;

/** The persisted account/profile state must be repaired before staffing can change. */
public class AdvisorConfigurationException extends DomainException {
    public AdvisorConfigurationException(String message) {
        super("ADVISOR_CONFIGURATION_CONFLICT", message);
    }
}
