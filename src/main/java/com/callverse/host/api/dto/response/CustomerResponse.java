package com.callverse.host.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Body returned by {@code GET /api/v1/customers/{id}}.
 *
 * <p>Part of the published contract: the Next.js client generates its TypeScript type from the
 * OpenAPI document this record produces, so a field rename here is a breaking change there.
 *
 * <p><strong>A host-layer type, deliberately, even though it mirrors an application-layer record
 * field for field.</strong> {@code CustomerProfile} lives in {@code core.application.interfaces} and
 * is shared with the AI tool API; returning it directly would make every future change to an
 * internal projection a change to the public contract, and would let the two audiences drift into
 * each other. The duplication is the price of being able to version them separately.
 *
 * <p><strong>What is absent is the point.</strong> No {@code churnRisk}: it feeds
 * {@code conversation.priority_score}, so a client that could read it could infer — and a client
 * that could write it could manufacture — queue position. No {@code isSimulated}, which is internal
 * bookkeeping, and no phone number, which nothing on this route needs.
 */
@Schema(description = "A customer's account, contracts and plans")
public record CustomerResponse(
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID id,
        @Schema(description = "The identifier used by the billing system", example = "CUST-00418")
                String externalRef,
        @Schema(example = "Amina") String firstName,
        @Schema(example = "Haddad") String lastName,
        @Schema(description = "Network zone, used to match outages", example = "Marseille") String zone,
        @Schema(description = "Months since the account opened", example = "18") int tenureMonths,
        List<Contract> contracts) {

    @Schema(description = "One subscription held by the customer")
    public record Contract(
            UUID id,
            @Schema(example = "ACTIVE", allowableValues = {"ACTIVE", "SUSPENDED", "TERMINATED"})
                    String status,
            LocalDate startedAt,
            @Schema(description = "Null while the contract is open") LocalDate endedAt,
            Plan plan) {}

    @Schema(description = "The tariff the contract is on")
    public record Plan(
            @Schema(example = "FIB_1G") String code,
            @Schema(example = "Fibre 1 Gb/s") String name,
            @Schema(example = "FIBER", allowableValues = {"MOBILE", "FIBER", "ADSL", "BUNDLE"})
                    String category,
            @Schema(example = "44.99") BigDecimal monthlyPrice,
            @Schema(description = "Null for a fixed-line plan", example = "150") Integer dataGb,
            @Schema(description = "Null for a mobile plan", example = "1000") Integer speedMbps) {}
}
