package com.callverse.core.application.features.customer.queries;

/** @param externalRef the bank's customer reference, as a customer reads it out on the phone */
public record FindCustomerByReferenceQuery(String externalRef) {}
