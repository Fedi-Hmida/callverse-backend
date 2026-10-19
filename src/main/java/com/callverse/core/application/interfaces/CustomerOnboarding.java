package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.CustomerSegment;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Opens a customer at the bank in one transaction: the login, the profile, a current account with
 * a valid IBAN, a debit card, and the opening deposit when there is one.
 */
public interface CustomerOnboarding {

    /**
     * @throws com.callverse.core.domain.exceptions.EmailAlreadyUsedException when the address is taken;
     *     nothing is written then
     */
    Onboarded open(NewCustomer customer);

    record NewCustomer(String email, String passwordHash, String firstName, String lastName, String phone,
                       String region, CustomerSegment segment, String productCode, BigDecimal initialDeposit,
                       UUID openedByUserId) {}

    record Onboarded(UUID customerId, UUID userId, String email, String externalRef, String maskedIban, String cardLast4) {}
}
