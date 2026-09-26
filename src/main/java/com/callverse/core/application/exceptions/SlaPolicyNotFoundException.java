package com.callverse.core.application.exceptions;

import java.util.UUID;

/**
 * No active SLA policy could be found for a skill.
 *
 * <p><strong>Why this is its own class rather than a {@link ResourceNotFoundException}.</strong>
 * That one emits {@code RESOURCE_NOT_FOUND}, and the inter-repository API contract names
 * {@code SLA_POLICY_NOT_FOUND} specifically. Clients branch on {@code code}, never on
 * {@code message}, so folding this into the generic 404 would make the two indistinguishable to a
 * caller that needs to tell "this skill has no policy configured" — an operational gap someone must
 * fix — from "you asked for a row that does not exist", which is an ordinary miss.
 *
 * <p><strong>It extends {@link ResourceNotFoundException}, not {@link ApplicationException},
 * deliberately.</strong> The status is decided by the handler for the type it extends, and a bare
 * {@code ApplicationException} maps to <strong>400</strong>. A 400 would tell the caller its request
 * was malformed when in fact the request was fine and the configuration is missing — so this
 * inherits the <strong>404</strong> mapping while still emitting its own contract code.
 *
 * <p>There is a schema caveat behind this. Nothing enforces uniqueness among active policies per
 * skill, so two active rows for one skill are representable with no defined winner. A caller that
 * silently picks one is making a choice the schema did not authorise; whoever throws this must also
 * decide, and document, what happens when more than one matches. That constraint is in the audit's
 * consolidated schema request.
 *
 * <p>Thrown by the SLA engine in Phase 4 and by KPI computation in Phase 6.
 */
public class SlaPolicyNotFoundException extends ResourceNotFoundException {

    private static final String CODE = "SLA_POLICY_NOT_FOUND";

    public SlaPolicyNotFoundException(UUID skillId) {
        super(CODE, "No active SLA policy is configured for skill %s.".formatted(skillId));
    }
}
