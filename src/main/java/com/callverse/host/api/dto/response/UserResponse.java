package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.UserAccounts.AccountPage;
import com.callverse.core.application.interfaces.UserAccounts.AccountRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An account as an administrator sees it. Never the password or its hash. */
@Schema(name = "User")
public record UserResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED, example = "karim.benali@bank.fr") String email,
        @Schema(requiredMode = REQUIRED, example = "Karim") String firstName,
        @Schema(requiredMode = REQUIRED, example = "Benali") String lastName,
        @Schema(requiredMode = REQUIRED, example = "ADVISOR", allowableValues = {"CUSTOMER", "ADVISOR", "SUPERVISOR", "ADMIN"})
                String role,
        @Schema(requiredMode = REQUIRED, description = "False once blocked: the account can neither log in nor act")
                boolean active,
        @Schema(requiredMode = REQUIRED) Instant createdAt) {

    public static UserResponse from(AccountRecord a) {
        return new UserResponse(a.id(), a.email(), a.firstName(), a.lastName(), a.role().name(), a.active(), a.createdAt());
    }

    /** A page of accounts, in the API's paging shape. */
    @Schema(name = "UserPage")
    public record Page(@Schema(requiredMode = REQUIRED) List<UserResponse> content, @Schema(requiredMode = REQUIRED) PageInfo page) {

        public static Page from(AccountPage p) {
            return new Page(p.content().stream().map(UserResponse::from).toList(),
                    new PageInfo(p.page(), p.size(), p.totalElements(), p.totalPages()));
        }
    }

    @Schema(name = "PageInfo")
    public record PageInfo(
            @Schema(requiredMode = REQUIRED) int number,
            @Schema(requiredMode = REQUIRED) int size,
            @Schema(requiredMode = REQUIRED) long totalElements,
            @Schema(requiredMode = REQUIRED) int totalPages) {}
}
