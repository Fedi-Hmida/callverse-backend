package com.callverse.host.api.controllers;

import com.callverse.core.application.features.auth.commands.LoginCommand;
import com.callverse.core.application.features.auth.commands.LoginCommandHandler;
import com.callverse.core.application.features.auth.commands.LoginResult;
import com.callverse.core.application.features.auth.commands.RegisterCustomerCommandHandler;
import com.callverse.core.application.features.auth.commands.SessionTokensHandler;
import com.callverse.core.application.features.auth.queries.GetCurrentUserQuery;
import com.callverse.core.application.features.auth.queries.GetCurrentUserQueryHandler;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.host.api.dto.request.LoginRequest;
import com.callverse.host.api.dto.request.RegisterCustomerRequest;
import com.callverse.host.api.dto.request.RefreshTokenRequest;
import com.callverse.host.api.dto.response.CurrentUserResponse;
import com.callverse.host.api.dto.response.TokenResponse;
import com.callverse.host.api.dto.response.SessionTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>The first {@code @PostMapping} in the repository, and the first endpoint whose failure mode
 * matters more than its success path.
 *
 * <p><strong>No security rule is declared here.</strong> Reachability is decided by the filter
 * chains in {@code SecurityConfiguration}: under {@code dev} every route is open; under any other
 * profile login is permitted to everyone and {@code /me} to any authenticated caller, and the rest
 * is denied. The JWT filter skips login, so a stale token attached to it does not block a fresh
 * login.
 *
 * <p><strong>The token is returned in the body, not in a cookie.</strong> That is not an
 * accident of convenience: the backend's CSRF protection is currently disabled, and the
 * justification recorded in {@code docs/user/JWT_AUTH_AUDIT.md} is precisely that no cookie exists
 * anywhere in the system. Issuing one here would silently invalidate that reasoning.
 */
@RestController
// produces is pinned so the published contract says application/json rather than the */*
// springdoc infers when a controller stays silent. The frontend client is generated from
// that document, so the media type is part of the contract, not a detail.
@RequestMapping(
        path = "/api/v1/auth",
        produces = MediaType.APPLICATION_JSON_VALUE,
        consumes = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Exchanging credentials for an access token")
public class AuthController {

    private final LoginCommandHandler login;
    private final GetCurrentUserQueryHandler currentUser;
    private final RegisterCustomerCommandHandler registration;
    private final SessionTokensHandler sessions;

    @PostMapping("/login")
    @Operation(
            operationId = "login",
            summary = "Log in",
            description =
                    "Exchanges an email and password for a signed JWT. An unknown email and a wrong "
                            + "password return the same 401 with code INVALID_CREDENTIALS, so the "
                            + "endpoint cannot be used to discover which addresses hold accounts. "
                            + "Deactivated accounts cannot authenticate.")
    public SessionTokenResponse login(@Valid @RequestBody LoginRequest request) {
        login.handle(new LoginCommand(request.email(), request.password()));
        return SessionTokenResponse.from(sessions.start(request.email()));
    }

    @PostMapping("/register")
    @Operation(operationId="registerCustomer",summary="Create my customer portal identity",description="Creates a CUSTOMER login and linked customer profile only; no bank account, balance or card is created.")
    public ResponseEntity<CurrentUserResponse> register(@Valid @RequestBody RegisterCustomerRequest request) {
        var user=registration.handle(request.email(),request.password(),request.firstName(),request.lastName(),request.region(),request.phone());
        return ResponseEntity.status(201).body(new CurrentUserResponse(user.userId(),user.email(),"CUSTOMER"));
    }

    @PostMapping("/refresh") @Operation(operationId="refreshSession",summary="Rotate my refresh credential")
    public SessionTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return SessionTokenResponse.from(sessions.refresh(request.refreshToken()));
    }

    @PostMapping("/logout") @Operation(operationId="logoutSession",summary="Revoke my refresh session and its access tokens")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        sessions.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    // consumes is widened because a GET carries no body; the class-level JSON constraint is for login.
    @GetMapping(path = "/me", consumes = MediaType.ALL_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "getCurrentUser",
            summary = "Who am I",
            description =
                    "Returns the account the presented bearer token identifies. Read from the token, "
                            + "not the database: a change made after login appears only after the "
                            + "next login. Without a valid token: 401 UNAUTHENTICATED.")
    public CurrentUserResponse me() {
        AuthenticatedPrincipal principal = currentUser.handle(new GetCurrentUserQuery());
        return new CurrentUserResponse(principal.userId(), principal.email(), principal.role().name());
    }
}
