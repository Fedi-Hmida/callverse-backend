package com.callverse.core.application.features.customer;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.CustomerOnboarding;
import com.callverse.core.application.interfaces.CustomerOnboarding.NewCustomer;
import com.callverse.core.application.interfaces.CustomerOnboarding.Onboarded;
import com.callverse.core.application.interfaces.PasswordHasher;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.services.PasswordPolicy;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Staff open a customer ({@code POST /api/v1/customers}): an advisor at the front desk, a
 * supervisor or an administrator. The customer gets a login with a temporary password, a profile,
 * a current account and a debit card, as a branch would open them.
 */
public class OnboardCustomerCommandHandler {

    /** Current accounts only: savings and loans are opened later, with the advisor. */
    static final Set<String> OPENING_PRODUCTS = Set.of("CUR_ESSENTIAL", "CUR_PREMIUM");
    static final BigDecimal MAX_DEPOSIT = new BigDecimal("1000000");
    private static final Set<UserRole> STAFF = Set.of(UserRole.ADVISOR, UserRole.SUPERVISOR, UserRole.ADMIN);

    private final CurrentPrincipalProvider principals;
    private final CustomerOnboarding onboarding;
    private final PasswordHasher hasher;

    public OnboardCustomerCommandHandler(CurrentPrincipalProvider principals, CustomerOnboarding onboarding, PasswordHasher hasher) {
        this.principals = Objects.requireNonNull(principals, "principals must not be null");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding must not be null");
        this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
    }

    public Onboarded handle(OnboardCustomerCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        var caller = principals.current().orElseThrow(AuthenticationRequiredException::new);
        if (!STAFF.contains(caller.role())) {
            throw new ActionNotPermittedException("Only bank staff open customers.");
        }
        String email = required(command.email(), "email").toLowerCase(Locale.ROOT);
        String firstName = required(command.firstName(), "firstName");
        String lastName = required(command.lastName(), "lastName");
        String region = required(command.region(), "region");
        CustomerSegment segment = segment(command.segment());
        String product = required(command.productCode(), "productCode").toUpperCase(Locale.ROOT);
        if (!OPENING_PRODUCTS.contains(product)) {
            throw new InvalidRequestException("A customer opens with a current account: CUR_ESSENTIAL or CUR_PREMIUM.");
        }
        BigDecimal deposit = command.initialDeposit() == null ? BigDecimal.ZERO : command.initialDeposit();
        if (deposit.signum() < 0 || deposit.compareTo(MAX_DEPOSIT) > 0 || deposit.scale() > 2) {
            throw new InvalidRequestException("The opening deposit is between 0 and 1 000 000, with at most 2 decimals.");
        }
        PasswordPolicy.violation(command.temporaryPassword(), email).ifPresent(reason -> {
            throw new InvalidRequestException(reason);
        });
        String phone = command.phone() == null || command.phone().isBlank() ? null : command.phone().strip();
        return onboarding.open(new NewCustomer(email, hasher.hash(command.temporaryPassword()), firstName, lastName,
                phone, region, segment, product, deposit, caller.userId()));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        return value.strip();
    }

    private static CustomerSegment segment(String value) {
        try {
            return CustomerSegment.valueOf(required(value, "segment").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException("segment is one of MASS, AFFLUENT, PRIVATE, PROFESSIONAL");
        }
    }
}
