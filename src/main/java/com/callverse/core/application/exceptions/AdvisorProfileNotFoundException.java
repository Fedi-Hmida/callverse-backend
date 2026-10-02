package com.callverse.core.application.exceptions;

import java.util.UUID;

/**
 * An ADVISOR login with no unambiguous advisor behind it: no {@code advisor} row links to it, or
 * more than one does. Nothing the advisor asked for can be answered — which queue, which
 * conversations — so it is a 404 with its own code, telling an administrator what to fix.
 */
public class AdvisorProfileNotFoundException extends ResourceNotFoundException {

    private static final String CODE = "ADVISOR_PROFILE_NOT_FOUND";

    public AdvisorProfileNotFoundException(UUID userId) {
        super(CODE, "No advisor profile is linked to account %s.".formatted(userId));
    }
}
