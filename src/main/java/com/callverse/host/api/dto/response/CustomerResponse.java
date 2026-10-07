package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
 * field for field.</strong> {@code CustomerProfile} lives in {@code core.application.interfaces}
 * and will serve the AI tool API again; returning it directly would make every future change to an
 * internal projection a change to the public contract, and would let the two audiences drift into
 * each other. The duplication is the price of being able to version them separately.
 *
 * <p><strong>What is absent is the point.</strong> No {@code churnRisk}: it feeds
 * {@code conversation.priority_score}, so a client that could read it could infer — and a client
 * that could write it could manufacture — queue position. No {@code isSimulated}, which is internal
 * bookkeeping, and no phone number, which nothing on this route needs. <strong>No full IBAN</strong>:
 * only {@link IbanMask}'s masked form leaves this route.
 */
@Schema(name = "CustomerResponse", description = "A bank customer, their accounts and the products they hold")
public record CustomerResponse(
        @Schema(requiredMode = REQUIRED, example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID id,
        @Schema(requiredMode = REQUIRED, description = "The bank's customer reference", example = "CUST-00418")
                String externalRef,
        @Schema(requiredMode = REQUIRED, example = "Amina") String firstName,
        @Schema(requiredMode = REQUIRED, example = "Haddad") String lastName,
        @Schema(requiredMode = REQUIRED, description = "Home region, used to match service outages", example = "Marseille")
                String region,
        @Schema(requiredMode = REQUIRED, example = "MASS",
                allowableValues = {"MASS", "AFFLUENT", "PRIVATE", "PROFESSIONAL"})
                String segment,
        @Schema(requiredMode = REQUIRED, description = "Months since the relationship began", example = "18")
                int tenureMonths,
        @Schema(requiredMode = REQUIRED) List<Account> accounts) {

    @Schema(name = "CustomerAccount", description = "One account held by the customer")
    public record Account(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(
                            requiredMode = REQUIRED,
                            description = "The IBAN with all but its first and last four characters masked",
                            example = "FR76 **** **** 0189")
                    String maskedIban,
            @Schema(requiredMode = REQUIRED, example = "EUR") String currency,
            @Schema(
                            requiredMode = REQUIRED,
                            description = "Signed: negative when overdrawn, and for a loan's outstanding capital",
                            example = "1523.40")
                    BigDecimal balance,
            @Schema(requiredMode = REQUIRED, example = "500.00") BigDecimal overdraftLimit,
            @Schema(requiredMode = REQUIRED, example = "ACTIVE", allowableValues = {"ACTIVE", "FROZEN", "CLOSED"})
                    String status,
            @Schema(requiredMode = REQUIRED) LocalDate openedAt,
            @Schema(nullable = true, description = "Null while the account is open") LocalDate closedAt,
            @Schema(requiredMode = REQUIRED) Product product,
            @Schema(requiredMode = REQUIRED, description = "Cards on this account; empty when there are none")
                    List<Card> cards) {}

    @Schema(name = "CustomerAccountCard", description = "A payment card: never more than its last four digits")
    public record Card(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED, example = "4242") String panLast4,
            @Schema(requiredMode = REQUIRED, example = "VISA", allowableValues = {"VISA", "MASTERCARD"}) String network,
            @Schema(requiredMode = REQUIRED, example = "DEBIT", allowableValues = {"DEBIT", "CREDIT"}) String type,
            @Schema(requiredMode = REQUIRED, example = "ACTIVE",
                    allowableValues = {"ACTIVE", "BLOCKED", "EXPIRED", "CANCELLED"})
                    String status,
            @Schema(requiredMode = REQUIRED) LocalDate expiresOn) {}

    @Schema(name = "CustomerAccountProduct", description = "The product the account is opened on")
    public record Product(
            @Schema(requiredMode = REQUIRED, example = "CUR_ESSENTIAL") String code,
            @Schema(requiredMode = REQUIRED, example = "Compte courant Essentiel") String name,
            @Schema(
                            requiredMode = REQUIRED,
                            example = "CURRENT_ACCOUNT",
                            allowableValues = {"CURRENT_ACCOUNT", "SAVINGS", "CONSUMER_LOAN", "MORTGAGE"})
                    String category) {}
}
