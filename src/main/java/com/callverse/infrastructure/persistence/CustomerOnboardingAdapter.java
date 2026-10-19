package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.CustomerOnboarding;
import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.BankTransaction;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.CardNetwork;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.CardType;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.exceptions.EmailAlreadyUsedException;
import com.callverse.core.domain.services.FrenchIban;
import com.callverse.infrastructure.persistence.repositories.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a new customer as one unit: login, profile, current account, debit card and opening deposit.
 * Any failure rolls the whole opening back, so a taken e-mail leaves nothing half-created.
 */
@Component
@RequiredArgsConstructor
class CustomerOnboardingAdapter implements CustomerOnboarding {

    /** CallVerse's own bank and branch codes for accounts opened in the application. */
    private static final String BANK = "30004";
    private static final String BRANCH = "00030";
    private static final Map<CustomerSegment, String> DAILY_CARD_LIMIT = Map.of(
            CustomerSegment.MASS, "1000.00", CustomerSegment.AFFLUENT, "2500.00",
            CustomerSegment.PRIVATE, "5000.00", CustomerSegment.PROFESSIONAL, "3000.00");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EntityManager em;
    private final AppUserRepository users;
    private final Clock clock;

    @Override
    @Transactional
    public Onboarded open(NewCustomer request) {
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new EmailAlreadyUsedException();
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        AppUser user = new AppUser();
        user.setEmail(request.email());
        user.setPasswordHash(request.passwordHash());
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setRole(UserRole.CUSTOMER);
        user.setActive(true);
        user.setCreatedAt(now);
        em.persist(user);

        Customer customer = new Customer();
        customer.setUser(user);
        customer.setExternalRef(uniqueReference());
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setPhone(request.phone());
        customer.setRegion(request.region());
        customer.setSegment(request.segment());
        customer.setTenureMonths(0);
        customer.setChurnRisk(ChurnRisk.LOW);
        customer.setSimulated(false);
        em.persist(customer);

        BankingProduct product = em.createQuery("select p from BankingProduct p where p.code = :code and p.active = true",
                        BankingProduct.class)
                .setParameter("code", request.productCode())
                .getResultStream().findFirst()
                .orElseThrow(() -> new InvalidRequestException("Unknown or inactive product: " + request.productCode()));
        Account account = new Account();
        account.setCustomer(customer);
        account.setProduct(product);
        account.setIban(uniqueIban());
        account.setBalance(request.initialDeposit());
        account.setOverdraftLimit(BigDecimal.ZERO);
        account.setStatus(AccountStatus.ACTIVE);
        account.setOpenedAt(today);
        em.persist(account);

        Card card = new Card();
        card.setAccount(account);
        card.setPanLast4(String.format("%04d", RANDOM.nextInt(10_000)));
        card.setNetwork(CardNetwork.VISA);
        card.setType(CardType.DEBIT);
        card.setStatus(CardStatus.ACTIVE);
        card.setExpiresOn(YearMonth.from(today).plusYears(4).atEndOfMonth());
        card.setDailyLimit(new BigDecimal(DAILY_CARD_LIMIT.get(request.segment())));
        em.persist(card);

        if (request.initialDeposit().signum() > 0) {
            BankTransaction deposit = new BankTransaction();
            deposit.setAccount(account);
            deposit.setType(TransactionType.TRANSFER_IN);
            deposit.setAmount(request.initialDeposit());
            deposit.setLabel("VERSEMENT A L'OUVERTURE");
            deposit.setCounterparty("Ouverture de compte");
            deposit.setStatus(TransactionStatus.BOOKED);
            deposit.setBookedAt(now);
            em.persist(deposit);
        }
        em.flush();
        return new Onboarded(customer.getId(), user.getId(), user.getEmail(), customer.getExternalRef(),
                masked(account.getIban()), card.getPanLast4());
    }

    private String uniqueReference() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String reference = String.format("CV-%08d", RANDOM.nextInt(100_000_000));
            if (em.createQuery("select count(c) from Customer c where c.externalRef = :r", Long.class)
                    .setParameter("r", reference).getSingleResult() == 0) {
                return reference;
            }
        }
        throw new IllegalStateException("No free customer reference after 10 attempts");
    }

    private String uniqueIban() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String iban = FrenchIban.of(BANK, BRANCH, String.format("%011d", Math.floorMod(RANDOM.nextLong(), 100_000_000_000L)));
            if (em.createQuery("select count(a) from Account a where a.iban = :i", Long.class)
                    .setParameter("i", iban).getSingleResult() == 0) {
                return iban;
            }
        }
        throw new IllegalStateException("No free IBAN after 10 attempts");
    }

    private static String masked(String iban) {
        return iban.substring(0, 4) + " **** **** " + iban.substring(iban.length() - 4);
    }
}
