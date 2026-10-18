package com.callverse.core.application.interfaces;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Customer-only identity resolution and registration; no client-selected customer identifier. */
public interface CustomerSelfService {
    record Registration(String email, String passwordHash, String firstName, String lastName, String region, String phone) {}
    record Registered(UUID userId, String email) {}
    Registered register(Registration registration);
    UUID customerId(UUID userId);
    List<TransactionSummary> statement(UUID customerId, LocalDate from, LocalDate to);
}
