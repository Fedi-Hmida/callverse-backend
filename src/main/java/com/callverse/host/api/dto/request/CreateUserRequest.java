package com.callverse.host.api.dto.request;

import com.callverse.core.domain.enums.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/admin/users}. */
@Schema(name = "CreateUserRequest")
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 180)
                @Pattern(regexp = "\\p{ASCII}+", message = "must contain only ASCII characters")
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "karim.benali@bank.fr")
                String email,
        @NotBlank @Size(max = 80) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Karim")
                String firstName,
        @NotBlank @Size(max = 80) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Benali")
                String lastName,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "ADVISOR") UserRole role,
        @NotBlank @Size(max = 200)
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password",
                        description = "Temporary password: 12 characters to 72 bytes, not the email's name. "
                                + "Never returned.")
                String password) {

    /** Never the password. */
    @Override
    public String toString() {
        return "CreateUserRequest[email=" + email + ", role=" + role + "]";
    }
}
