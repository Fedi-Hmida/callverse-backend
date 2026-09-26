package com.callverse.host.api.dto.response.internal;

import com.callverse.core.application.interfaces.CustomerProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tool response for {@code GET /internal/customers/{id}}.
 *
 * <p><strong>Provisional.</strong> The project context fixes the route and one line of intent
 * ("profile and contract"), not the fields. This shape is the backend's proposal to the AI lot and
 * is frozen only once the agents consume it.
 */
@Schema(description = "Customer profile for the AI agent. Provisional until agreed with the AI lot.")
public record CustomerProfileResponse(
        @Schema(example = "3b734ad0-5dd8-41bf-9722-b0d9ac278824") UUID id,
        @Schema(example = "EXT-7F3A21C9B04D") String externalRef,
        @Schema(example = "Amira") String firstName,
        @Schema(example = "Ben Salem") String lastName,
        @Schema(example = "PARIS-15") String zone,
        @Schema(example = "26") int tenureMonths,
        @Schema(description = "Newest first") List<ContractResponse> contracts) {

    public record ContractResponse(
            UUID id,
            @Schema(example = "ACTIVE") String status,
            LocalDate startedAt,
            @Schema(nullable = true) LocalDate endedAt,
            PlanResponse plan) {}

    public record PlanResponse(
            @Schema(example = "FIB_1G") String code,
            @Schema(example = "Fibre 1 Gb/s") String name,
            @Schema(example = "FIBER") String category,
            @Schema(example = "44.99") BigDecimal monthlyPrice,
            @Schema(nullable = true) Integer dataGb,
            @Schema(nullable = true) Integer speedMbps) {}

    public static CustomerProfileResponse from(CustomerProfile profile) {
        return new CustomerProfileResponse(
                profile.id(),
                profile.externalRef(),
                profile.firstName(),
                profile.lastName(),
                profile.zone(),
                profile.tenureMonths(),
                profile.contracts().stream()
                        .map(c -> new ContractResponse(
                                c.id(),
                                c.status().name(),
                                c.startedAt(),
                                c.endedAt(),
                                new PlanResponse(
                                        c.plan().code(),
                                        c.plan().name(),
                                        c.plan().category().name(),
                                        c.plan().monthlyPrice(),
                                        c.plan().dataGb(),
                                        c.plan().speedMbps())))
                        .toList());
    }
}
