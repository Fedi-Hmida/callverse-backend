package com.callverse.core.application.features.customer;

import java.math.BigDecimal;

/** What staff enter to open a customer; validated by {@link OnboardCustomerCommandHandler}. */
public record OnboardCustomerCommand(String email, String firstName, String lastName, String phone, String region,
                                     String segment, String productCode, BigDecimal initialDeposit,
                                     String temporaryPassword) {

    /** The password never appears in logs or error reports. */
    @Override
    public String toString() {
        return "OnboardCustomerCommand[email=" + email + ", segment=" + segment + ", productCode=" + productCode + "]";
    }
}
