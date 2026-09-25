package com.callverse.host.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The caller, as the presented access token identifies them.
 *
 * <p>Reflects the token, not the database at this instant: a role changed after login shows here
 * only once the client logs in again.
 *
 * @param userId the account identifier ({@code app_user.id})
 * @param email the account's email
 * @param role one of CUSTOMER, ADVISOR, SUPERVISOR, ADMIN
 */
@Schema(description = "The authenticated caller, read from the verified access token")
public record CurrentUserResponse(
        @Schema(example = "7d3f0c52-6a4e-4b8e-9d1f-2c5a8e9b0f13") UUID userId,
        @Schema(example = "advisor@callverse.local") String email,
        @Schema(example = "ADVISOR") String role) {}
