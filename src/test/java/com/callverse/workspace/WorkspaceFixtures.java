package com.callverse.workspace;

import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.BankTransaction;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.KbArticle;
import com.callverse.core.domain.entities.ServiceIncident;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.BankingService;
import com.callverse.core.domain.enums.CardNetwork;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.CardType;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
import com.callverse.core.domain.enums.UserRole;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Builds the banking rows the workspace routes read and write. Every value that must be unique
 * across tests is suffixed, so tests never collide inside the shared container.
 */
final class WorkspaceFixtures {

    private final EntityManager em;

    WorkspaceFixtures(EntityManager em) {
        this.em = em;
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    Customer customer(String externalRef, String region) {
        Customer customer = new Customer();
        customer.setExternalRef(externalRef);
        customer.setFirstName("Amina");
        customer.setLastName("Haddad");
        customer.setRegion(region);
        em.persist(customer);
        return customer;
    }

    Account account(Customer owner, String iban) {
        BankingProduct product = em.createQuery(
                        "select p from BankingProduct p where p.code = 'CUR_ESSENTIAL'", BankingProduct.class)
                .getSingleResult();
        Account account = new Account();
        account.setCustomer(owner);
        account.setProduct(product);
        account.setIban(iban);
        account.setBalance(new BigDecimal("1523.40"));
        account.setStatus(AccountStatus.ACTIVE);
        account.setOpenedAt(LocalDate.of(2020, 3, 1));
        em.persist(account);
        return account;
    }

    static String iban() {
        return "FR76" + UUID.randomUUID().toString().replace("-", "").substring(0, 23).toUpperCase();
    }

    Card card(Account account, CardStatus status) {
        Card card = new Card();
        card.setAccount(account);
        card.setPanLast4("4242");
        card.setNetwork(CardNetwork.VISA);
        card.setType(CardType.DEBIT);
        card.setStatus(status);
        card.setExpiresOn(LocalDate.of(2029, 12, 31));
        card.setDailyLimit(new BigDecimal("1000.00"));
        em.persist(card);
        return card;
    }

    BankTransaction movement(Account account, String label, String counterparty, Instant bookedAt) {
        BankTransaction t = new BankTransaction();
        t.setAccount(account);
        t.setType(TransactionType.TRANSFER_OUT);
        t.setAmount(new BigDecimal("-42.00"));
        t.setLabel(label);
        t.setCounterparty(counterparty);
        t.setStatus(TransactionStatus.BOOKED);
        t.setBookedAt(bookedAt);
        em.persist(t);
        return t;
    }

    ServiceIncident incident(BankingService service, String region, UUID runId, String description) {
        ServiceIncident incident = new ServiceIncident();
        incident.setService(service);
        incident.setRegion(region);
        incident.setSeverity((short) 2);
        incident.setDescription(description);
        incident.setStartedAt(Instant.now().minusSeconds(900));
        incident.setRunId(runId);
        em.persist(incident);
        return incident;
    }

    KbArticle article(String title, boolean published) {
        KbArticle article = new KbArticle();
        article.setCategory("CARDS");
        article.setTitle(title);
        article.setContent("Procedure: " + title);
        article.setTags(new String[] {"carte"});
        article.setPublished(published);
        em.persist(article);
        return article;
    }

    /** A login, so that a token can carry a user id the database knows. */
    AppUser user(UserRole role) {
        AppUser user = new AppUser();
        user.setEmail(role.name().toLowerCase() + "-" + suffix() + "@test.local");
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuuJ4H1y9oD6kBz1V5Q2yQ1w5bLb6xXyZ2");
        user.setFirstName("Test");
        user.setLastName(role.name());
        user.setRole(role);
        em.persist(user);
        return user;
    }

    /** An advisor behind {@code user}: the only kind of advisor who may act on a conversation. */
    Advisor advisor(AppUser user) {
        Advisor advisor = new Advisor();
        advisor.setUser(user);
        advisor.setDisplayName("Karim " + suffix());
        em.persist(advisor);
        return advisor;
    }

    /** A conversation assigned to {@code advisor}. */
    Conversation conversation(Customer customer, ConversationStatus status, Advisor advisor) {
        Conversation conversation = conversation(customer, status);
        conversation.setAdvisor(advisor);
        conversation.setAssignedAt(Instant.now());
        return conversation;
    }

    Conversation conversation(Customer customer, ConversationStatus status) {
        Conversation conversation = new Conversation();
        conversation.setCustomer(customer);
        conversation.setStatus(status);
        em.persist(conversation);
        return conversation;
    }
}
