package com.callverse.host.api.dto.response;

import com.callverse.core.application.interfaces.CustomerOnboarding.Onboarded;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The opened customer: what staff read back to the customer at the desk. */
@Schema(name = "CustomerOnboarded")
public record CustomerOnboardedResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID customerId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "CV-04821937") String externalRef,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "FR76 **** **** 0142") String maskedIban,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "4821") String cardLast4) {

    public static CustomerOnboardedResponse from(Onboarded o) {
        return new CustomerOnboardedResponse(o.customerId(), o.email(), o.externalRef(), o.maskedIban(), o.cardLast4());
    }
}
