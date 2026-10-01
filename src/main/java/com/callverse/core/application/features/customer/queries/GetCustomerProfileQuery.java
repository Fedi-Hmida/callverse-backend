package com.callverse.core.application.features.customer.queries;

import java.util.UUID;

/** The profile and accounts of one customer. */
public record GetCustomerProfileQuery(UUID customerId) {}
