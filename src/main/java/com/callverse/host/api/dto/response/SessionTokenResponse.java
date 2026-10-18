package com.callverse.host.api.dto.response;

import com.callverse.core.application.features.auth.commands.SessionTokensHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description="Access credentials and a rotating, revocable refresh credential; never stored in cookies")
public record SessionTokenResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Signed JWT bearer access credential") String token,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant expiresAt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, allowableValues={"CUSTOMER","ADVISOR","SUPERVISOR","ADMIN"}) String role,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Opaque credential; rotate once and replace atomically") String refreshToken,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant refreshExpiresAt) {
    public static SessionTokenResponse from(SessionTokensHandler.Tokens tokens) {
        return new SessionTokenResponse(tokens.token(),tokens.expiresAt(),tokens.role(),tokens.refreshToken(),tokens.refreshExpiresAt());
    }
    @Override public String toString() { return "SessionTokenResponse[redacted]"; }
}
