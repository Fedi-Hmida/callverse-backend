package com.callverse.infrastructure.demo;

import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.entities.AdvisorSkill;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.BankTransaction;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.KbArticle;
import com.callverse.core.domain.entities.ServiceIncident;
import com.callverse.core.domain.entities.Skill;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.AdvisorStatus;
import com.callverse.core.domain.enums.BankingService;
import com.callverse.core.domain.enums.CardNetwork;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.CardType;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
import com.callverse.core.domain.services.PriorityCalculator;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo dataset behind the advisor-workspace scenario: Amina Haddad calls on a Friday evening
 * because her card was declined in a Marseille supermarket.
 *
 * <p><strong>Never a Flyway migration.</strong> Flyway runs in every profile, so data seeded there
 * reaches every environment — the mistake V2's seed accounts already made. This runs only under the
 * {@code dev} profile <em>and</em> only when {@code callverse.demo-data.enabled=true}; both default
 * to off for anything else.
 *
 * <p><strong>Idempotent.</strong> Every boot may run it; it does nothing once the scenario's customer
 * exists. Deleting that one row and rebooting reloads everything, articles and outage included —
 * remove the whole dataset, not part of it. Customer references start with {@code DEMO-} so the rows are easy to recognise and remove.
 *
 * <p><strong>No real data.</strong> Names are invented; IBANs follow the documentation format; card
 * numbers do not exist anywhere, only last-four digits.
 *
 * <p><strong>The people.</strong> The development advisor login becomes Karim, an advisor holding
 * the four skills, and Amina's call is his; the development customer login becomes Amina's. This
 * step checks each link on every boot, so a database loaded before it existed is completed rather
 * than reloaded.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "callverse.demo-data.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
class DemoDataLoader implements ApplicationRunner {

    static final String SCENARIO_CUSTOMER = "DEMO-00418";
    private static final String ADVISOR_LOGIN = "advisor@callverse.local";
    private static final String CUSTOMER_LOGIN = "customer@callverse.local";

    private final EntityManager em;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            boolean present = !em.createQuery(
                            "select c.id from Customer c where c.externalRef = :ref")
                    .setParameter("ref", SCENARIO_CUSTOMER)
                    .getResultList()
                    .isEmpty();
            if (present) {
                log.info("Demo data already present; nothing loaded");
                return;
            }
            load(clock.instant());
            log.info("Demo data loaded: scenario customer {}", SCENARIO_CUSTOMER);
        });
        transactions.executeWithoutResult(status -> staff(clock.instant()));
    }

    /** Karim for the advisor login, Amina for the customer login; idempotent, link by link. */
    private void staff(Instant now) {
        AppUser advisorLogin = login(ADVISOR_LOGIN);
        AppUser customerLogin = login(CUSTOMER_LOGIN);
        Customer amina = em.createQuery("select c from Customer c where c.externalRef = :ref", Customer.class)
                .setParameter("ref", SCENARIO_CUSTOMER)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (advisorLogin == null || customerLogin == null || amina == null) {
            log.warn("Demo staffing skipped: a development login or the scenario customer is missing");
            return;
        }

        List<Advisor> existing = em.createQuery("select a from Advisor a where a.user = :u", Advisor.class)
                .setParameter("u", advisorLogin)
                .getResultList();
        if (existing.size() > 1) {
            // The advisor directory refuses an ambiguous login; the demo does not pick one either.
            log.warn("Demo staffing skipped: the development advisor login is linked to {} advisors", existing.size());
            return;
        }
        Advisor karim = existing.isEmpty() ? karim(advisorLogin) : existing.get(0);

        if (amina.getUser() == null) {
            amina.setUser(customerLogin);
        }
        for (Conversation call : em.createQuery(
                        "select c from Conversation c where c.customer = :c and c.status = :s and c.advisor is null",
                        Conversation.class)
                .setParameter("c", amina)
                .setParameter("s", ConversationStatus.ACTIVE)
                .getResultList()) {
            call.setAdvisor(karim);
            call.setAssignedAt(now.minus(Duration.ofMinutes(2)));
        }
    }

    private Advisor karim(AppUser login) {
        Advisor karim = new Advisor();
        karim.setUser(login);
        karim.setDisplayName("Karim Benali");
        karim.setStatus(AdvisorStatus.AVAILABLE);
        karim.setMaxConcurrent(2);
        em.persist(karim);
        for (String code : List.of("ACCOUNTS", "CARDS", "CREDIT", "FRAUD")) {
            AdvisorSkill held = new AdvisorSkill();
            held.setAdvisor(karim);
            held.setSkill(skill(code));
            held.setLevel((short) 2);
            em.persist(held);
        }
        log.info("Demo advisor created for the development advisor login");
        return karim;
    }

    private AppUser login(String email) {
        return em.createQuery("select u from AppUser u where u.email = :e", AppUser.class)
                .setParameter("e", email)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private void load(Instant now) {
        // --- Amina Haddad: the scenario ------------------------------------------------------
        Customer amina = customer(SCENARIO_CUSTOMER, "Amina", "Haddad", "Marseille",
                CustomerSegment.AFFLUENT, 72, ChurnRisk.MEDIUM);
        Account current = account(amina, "CUR_PREMIUM", "FR7630006000011234567890189",
                "1523.40", "500.00", LocalDate.of(2020, 3, 2));
        account(amina, "SAV_LIVRET", "FR7630006000019876543210144", "8200.00", "0.00", LocalDate.of(2021, 6, 15));
        Card visa = card(current, "4242", LocalDate.of(2028, 6, 30));

        movement(current, null, TransactionType.TRANSFER_IN, "2850.00", "VIREMENT SALAIRE SEPTEMBRE",
                "ACME Logistique", TransactionStatus.BOOKED, now.minus(Duration.ofDays(5)));
        movement(current, null, TransactionType.DIRECT_DEBIT, "-950.00", "LOYER OCTOBRE",
                "SCI Les Calanques FR7612345678901234567890123", TransactionStatus.BOOKED, now.minus(Duration.ofDays(1)));
        movement(current, visa, TransactionType.CARD_PAYMENT, "-249.00", "ONLINE STORE VILNIUS",
                null, TransactionStatus.PENDING, now.minus(Duration.ofMinutes(55)));
        movement(current, visa, TransactionType.CARD_PAYMENT, "-312.50", "ONLINE STORE VILNIUS",
                null, TransactionStatus.PENDING, now.minus(Duration.ofMinutes(48)));
        movement(current, visa, TransactionType.CARD_PAYMENT, "-89.90", "CB MARKET MARSEILLE",
                null, TransactionStatus.REJECTED, now.minus(Duration.ofMinutes(2)));

        Conversation call = new Conversation();
        call.setCustomer(amina);
        call.setSkill(skill("CARDS"));
        call.setIntent(Intent.CARD);
        call.setStatus(ConversationStatus.ACTIVE);
        em.persist(call);

        // --- Two other customers, so a lookup is a real lookup --------------------------------
        Customer lucas = customer("DEMO-00512", "Lucas", "Martin", "Lyon", CustomerSegment.MASS, 14, ChurnRisk.LOW);
        account(lucas, "CUR_ESSENTIAL", "FR7630006000015555666677701", "212.75", "200.00", LocalDate.of(2025, 8, 1));
        Customer sofia = customer("DEMO-00733", "Sofia", "Rossi", "Paris", CustomerSegment.PROFESSIONAL, 40, ChurnRisk.HIGH);
        account(sofia, "CUR_PREMIUM", "FR7630006000014444333322204", "-35.10", "1500.00", LocalDate.of(2022, 1, 10));

        // --- Two contacts waiting, so an advisor can take one live ---------------------------
        waiting(lucas, "ACCOUNTS", Intent.BALANCE, now.minus(Duration.ofSeconds(40)));
        waiting(sofia, "CARDS", Intent.CARD, now.minus(Duration.ofSeconds(75)));

        // --- An outage elsewhere: Marseille has none, so the scenario takes the fraud path -----
        ServiceIncident lyon = new ServiceIncident();
        lyon.setService(BankingService.ONLINE_BANKING);
        lyon.setRegion("Lyon");
        lyon.setSeverity((short) 3);
        lyon.setDescription("Connexion a la banque en ligne indisponible");
        lyon.setStartedAt(now.minus(Duration.ofMinutes(40)));
        lyon.setEstimatedEnd(now.plus(Duration.ofHours(1)));
        lyon.setAffectedCount(1800);
        em.persist(lyon);

        // --- Procedures the advisor searches for ----------------------------------------------
        article("CARDS", "Faire opposition a votre carte",
                "Bloquer la carte immediatement avec le motif adapte (perte, vol, fraude suspectee), puis "
                        + "ouvrir un ticket FRAUD si des paiements ne sont pas reconnus.",
                true, "carte", "opposition", "fraude");
        article("FRAUD", "Contester un paiement par carte",
                "Les paiements non reconnus sont contestes apres opposition; le client signe une declaration.",
                true, "contestation", "fraude", "paiement");
        article("CARDS", "Plafonds de paiement et de retrait",
                "Le plafond journalier est modifiable par un conseiller dans la limite du produit.",
                true, "plafond", "carte");
        article("CARDS", "Brouillon - nouvelle procedure carte virtuelle",
                "Brouillon non publie.", false, "brouillon");
    }

    private Customer customer(String ref, String first, String last, String region,
            CustomerSegment segment, int tenure, ChurnRisk risk) {
        Customer customer = new Customer();
        customer.setExternalRef(ref);
        customer.setFirstName(first);
        customer.setLastName(last);
        customer.setRegion(region);
        customer.setSegment(segment);
        customer.setTenureMonths(tenure);
        customer.setChurnRisk(risk);
        em.persist(customer);
        return customer;
    }

    private Account account(Customer owner, String productCode, String iban, String balance,
            String overdraft, LocalDate openedAt) {
        Account account = new Account();
        account.setCustomer(owner);
        account.setProduct(em.createQuery("select p from BankingProduct p where p.code = :c", BankingProduct.class)
                .setParameter("c", productCode)
                .getSingleResult());
        account.setIban(iban);
        account.setBalance(new BigDecimal(balance));
        account.setOverdraftLimit(new BigDecimal(overdraft));
        account.setStatus(AccountStatus.ACTIVE);
        account.setOpenedAt(openedAt);
        em.persist(account);
        return account;
    }

    private Card card(Account account, String last4, LocalDate expiresOn) {
        Card card = new Card();
        card.setAccount(account);
        card.setPanLast4(last4);
        card.setNetwork(CardNetwork.VISA);
        card.setType(CardType.DEBIT);
        card.setStatus(CardStatus.ACTIVE);
        card.setExpiresOn(expiresOn);
        card.setDailyLimit(new BigDecimal("1000.00"));
        em.persist(card);
        return card;
    }

    private void movement(Account account, Card card, TransactionType type, String amount, String label,
            String counterparty, TransactionStatus status, Instant bookedAt) {
        BankTransaction t = new BankTransaction();
        t.setAccount(account);
        t.setCard(card);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setLabel(label);
        t.setCounterparty(counterparty);
        t.setStatus(status);
        t.setBookedAt(bookedAt);
        em.persist(t);
    }

    private void waiting(Customer customer, String skillCode, Intent intent, Instant queuedAt) {
        Conversation contact = new Conversation();
        contact.setCustomer(customer);
        contact.setSkill(skill(skillCode));
        contact.setIntent(intent);
        contact.setStatus(ConversationStatus.QUEUED);
        contact.setPriorityScore(PriorityCalculator.score(customer.getChurnRisk(), customer.getSegment(), intent));
        contact.setQueuedAt(queuedAt);
        em.persist(contact);
    }

    private Skill skill(String code) {
        return em.createQuery("select s from Skill s where s.code = :c", Skill.class)
                .setParameter("c", code)
                .getSingleResult();
    }

    private void article(String category, String title, String content, boolean published, String... tags) {
        KbArticle article = new KbArticle();
        article.setCategory(category);
        article.setTitle(title);
        article.setContent(content);
        article.setTags(tags);
        article.setPublished(published);
        em.persist(article);
    }
}
