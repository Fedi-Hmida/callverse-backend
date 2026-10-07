package com.callverse.infrastructure.security;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Backs {@link CurrentPrincipalProvider} with Spring Security's {@code SecurityContextHolder}.
 *
 * <p>This is the only class that reads the holder on behalf of {@code core}; the ArchUnit rule
 * {@code core_must_not_depend_on_spring} is why the read happens here rather than in a handler.
 *
 * <p>Only a principal placed by {@link JwtAuthenticationFilter} counts. Spring's anonymous token
 * (principal {@code "anonymousUser"}) and an absent authentication both answer empty, so a handler
 * never mistakes "nobody" for a caller.
 *
 * <p>Package-private, like the other adapters: callers name the port, never this class.
 */
@Component
class SecurityContextPrincipalProvider implements CurrentPrincipalProvider {

    @Override
    public Optional<AuthenticatedPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}
