package com.callverse.core.application.features.customer.queries;

import java.util.UUID;

/**
 * @param customerId whose movements
 * @param count how many, most recent first; null means the default
 */
public record GetRecentTransactionsQuery(UUID customerId, Integer count) {}
