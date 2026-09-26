package com.callverse.core.application.features.customer.queries;

import java.util.UUID;

/**
 * @param customerId whose invoices
 * @param count how many, most recent first; null means the default
 */
public record GetRecentInvoicesQuery(UUID customerId, Integer count) {}
