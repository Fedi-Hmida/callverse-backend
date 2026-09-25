package com.callverse.host.api.controllers;

import com.callverse.core.application.features.auth.commands.LoginCommand;
import com.callverse.core.application.features.auth.commands.LoginCommandHandler;
import com.callverse.core.application.features.auth.commands.LoginResult;
import com.callverse.host.api.dto.request.LoginRequest;
import com.callverse.host.api.dto.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
 * <p><strong>No security rule is declared here.</strong> Under the {@code dev} profile the filter
 * chain permits every request, so this endpoint is reachable as-is; under any other profile the
 * default chain denies everything except the health probe, so it is not. Making login reachable in
 * a deny-by-default deployment needs an explicit permit rule on the chain, and that belongs with
 * sub-phase 2.3, alongside the authentication entry point that gives 401s the standard envelope.
 * Adding it here would be half of a change whose other half does not exist yet.
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

    @PostMapping("/login")
    @Operation(
            summary = "Log in",
            description =
                    "Exchanges an email and password for a signed JWT. An unknown email and a wrong "
                            + "password return the same 401 with code INVALID_CREDENTIALS, so the "
                            + "endpoint cannot be used to discover which addresses hold accounts. "
                            + "Deactivated accounts cannot authenticate.")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = login.handle(new LoginCommand(request.email(), request.password()));
        return new TokenResponse(result.token(), result.expiresAt(), result.role().name());
    }
}
