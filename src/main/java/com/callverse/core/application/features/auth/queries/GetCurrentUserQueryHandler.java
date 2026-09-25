package com.callverse.core.application.features.auth.queries;

import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import java.util.Objects;

/**
 * Answers {@link GetCurrentUserQuery}: who does the presented token say is calling?
 *
 * <p>The first consumer of {@link CurrentPrincipalProvider}, and the shape every ownership-checking
 * handler from sub-phase 2.5 will copy: take the port in the constructor, ask it for the caller,
 * refuse when there is none. Plain Java, wired in {@code AuthFeatureConfiguration}.
 *
 * <p>It returns what the token says, not what the database says now. A role changed or an account
 * deactivated after the token was issued is not visible here until the token expires — see
 * {@code JwtAuthenticationFilter} for the window this accepts.
 */
public class GetCurrentUserQueryHandler {

    private final CurrentPrincipalProvider principals;

    public GetCurrentUserQueryHandler(CurrentPrincipalProvider principals) {
        this.principals = Objects.requireNonNull(principals, "principals must not be null");
    }

    public AuthenticatedPrincipal handle(GetCurrentUserQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return principals.current().orElseThrow(AuthenticationRequiredException::new);
    }
}
