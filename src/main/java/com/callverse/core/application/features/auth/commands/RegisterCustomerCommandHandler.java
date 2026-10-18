package com.callverse.core.application.features.auth.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.CustomerSelfService;
import com.callverse.core.application.interfaces.PasswordHasher;
import com.callverse.core.domain.services.PasswordPolicy;
import java.util.Locale;

public class RegisterCustomerCommandHandler {
    private final CustomerSelfService customers;
    private final PasswordHasher passwords;
    public RegisterCustomerCommandHandler(CustomerSelfService customers, PasswordHasher passwords) {
        this.customers = customers;
        this.passwords = passwords;
    }
    public CustomerSelfService.Registered handle(String email, String password, String firstName, String lastName, String region, String phone) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        PasswordPolicy.violation(password, normalized).ifPresent(reason -> { throw new InvalidRequestException(reason); });
        return customers.register(new CustomerSelfService.Registration(normalized, passwords.hash(password),
                firstName.strip(), lastName.strip(), region.strip(), phone == null ? null : phone.strip()));
    }
}
