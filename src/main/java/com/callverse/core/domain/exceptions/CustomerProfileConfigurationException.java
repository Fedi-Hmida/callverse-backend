package com.callverse.core.domain.exceptions;

public class CustomerProfileConfigurationException extends DomainException {
    public CustomerProfileConfigurationException() {
        super("CUSTOMER_PROFILE_CONFLICT", "Multiple real customer profiles are linked to this login; repair the duplicate first.");
    }
}
