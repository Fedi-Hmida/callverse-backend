package com.callverse.host.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Body of {@code POST /api/v1/customers}: what a branch needs to open a customer. */
@Schema(name = "OnboardCustomerRequest")
public record OnboardCustomerRequest(
        @NotBlank @Email @Size(max = 180) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "nadia.benali@example.fr") String email,
        @NotBlank @Size(max = 80) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Nadia") String firstName,
        @NotBlank @Size(max = 80) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Benali") String lastName,
        @Size(max = 30) @Schema(example = "+33 6 12 34 56 78") String phone,
        @NotBlank @Size(max = 40) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Lyon") String region,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MASS", "AFFLUENT", "PRIVATE", "PROFESSIONAL"}) String segment,
        @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"CUR_ESSENTIAL", "CUR_PREMIUM"}) String productCode,
        @DecimalMin("0.00") @DecimalMax("1000000.00") @Schema(description = "Opening deposit in euros; 0 or absent for none", example = "250.00") BigDecimal initialDeposit,
        @NotBlank @Size(min = 12, max = 72) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Temporary password, given to the customer in person") String temporaryPassword) {
    @Override public String toString() { return "OnboardCustomerRequest[email=" + email + ", credentials=redacted]"; }
}
