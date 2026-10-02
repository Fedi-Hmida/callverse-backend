package com.callverse.core.domain.services;

import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.Intent;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A conversation's place in its skill queue: higher is served first.
 *
 * <p>Brief 02 asks for a score combining time in the queue, churn risk, client value and
 * criticality. Three of the four are known when the contact arrives and are scored here, once, into
 * {@code conversation.priority_score}:
 *
 * <ul>
 *   <li><strong>Churn risk</strong> — LOW 0, MEDIUM 20, HIGH 40. Losing a customer costs the most.
 *   <li><strong>Client value</strong> (segment) — MASS 0, PROFESSIONAL 10, AFFLUENT 15, PRIVATE 25.
 *   <li><strong>Criticality</strong> (intent) — FRAUD 30 (money is leaving now), ACCOUNT_CLOSURE 20
 *       (a retention call), CARD 10, CREDIT 5, BALANCE and OTHER 0. An unclassified contact scores 0.
 * </ul>
 *
 * <p><strong>Time in the queue is the tiebreak, not a term.</strong> A stored score cannot age by
 * itself, and recomputing it on read would defeat {@code idx_conv_status_queue}. The queue query
 * orders by score, then by {@code queued_at} ascending, so equal scores are served first-come,
 * first-served. Ageing a score while it waits needs the SLA sweep, which is a later phase.
 *
 * <p>The maximum is 95, well inside {@code NUMERIC(6,2)}. {@code churn_risk} feeds this score, which
 * is why it must never be writable by a customer (ownership rule A2).
 */
public final class PriorityCalculator {

    private PriorityCalculator() {
        // Utility holder for a pure function; never instantiated.
    }

    public static BigDecimal score(ChurnRisk churnRisk, CustomerSegment segment, Intent intent) {
        int score = churn(churnRisk) + value(segment) + criticality(intent);
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static int churn(ChurnRisk risk) {
        return switch (risk == null ? ChurnRisk.LOW : risk) {
            case LOW -> 0;
            case MEDIUM -> 20;
            case HIGH -> 40;
        };
    }

    private static int value(CustomerSegment segment) {
        return switch (segment == null ? CustomerSegment.MASS : segment) {
            case MASS -> 0;
            case PROFESSIONAL -> 10;
            case AFFLUENT -> 15;
            case PRIVATE -> 25;
        };
    }

    private static int criticality(Intent intent) {
        if (intent == null) {
            return 0;
        }
        return switch (intent) {
            case FRAUD -> 30;
            case ACCOUNT_CLOSURE -> 20;
            case CARD -> 10;
            case CREDIT -> 5;
            case BALANCE, OTHER -> 0;
        };
    }
}
