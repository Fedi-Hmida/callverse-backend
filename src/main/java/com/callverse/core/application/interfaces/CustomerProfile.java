package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.ContractStatus;
import com.callverse.core.domain.enums.PlanCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a first-line agent needs to know about a customer, and deliberately nothing else.
 *
 * <p><strong>Absent on purpose.</strong> {@code churn_risk} feeds the queue's priority score, so an
 * agent that sees it can learn to game the queue. {@code is_simulated} would tell an agent it is
 * inside an experiment. {@code user_id} and {@code phone} are not needed to resolve a request.
 */
public record CustomerProfile(
        UUID id,
        String externalRef,
        String firstName,
        String lastName,
        String zone,
        int tenureMonths,
        List<Contract> contracts) {

    public record Contract(
            UUID id, ContractStatus status, LocalDate startedAt, LocalDate endedAt, Plan plan) {}

    public record Plan(
            String code,
            String name,
            PlanCategory category,
            BigDecimal monthlyPrice,
            Integer dataGb,
            Integer speedMbps) {}
}
