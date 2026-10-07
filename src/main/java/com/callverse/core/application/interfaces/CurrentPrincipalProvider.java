package com.callverse.core.application.interfaces;

import java.util.Optional;

/**
 * Tells a use case who is calling.
 *
 * <p><strong>Why a port and not {@code SecurityContextHolder}.</strong> The holder lives under
 * {@code org.springframework.security..}, which the ArchUnit rule
 * {@code core_must_not_depend_on_spring} forbids anywhere in {@code core}. Every handler that
 * enforces an ownership rule needs the caller, so without this port those handlers could not be
 * written at all. The adapter reads the holder from {@code infrastructure.security}.
 *
 * <p><strong>What it cannot answer yet.</strong> It returns the account, not the customer or
 * advisor row behind it. That resolution is blocked on schema change S-1; the reason is recorded on
 * {@link AuthenticatedPrincipal} and in {@code docs/user/OWNERSHIP_RULES.md} §0.
 */
public interface CurrentPrincipalProvider {

    /**
     * @return the authenticated caller, or empty when the request carries no verified token — for
     *     instance an anonymous request on a route the filter chain permits
     */
    Optional<AuthenticatedPrincipal> current();
}
