package com.callverse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.TransactionSummary;
import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.BankTransaction;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CustomerRecords#findRecentTransactions} against real rows: the customer is the only input,
 * so another customer's movements can never appear, every account of this customer is covered, and
 * the newest movement comes first ({@code OWNERSHIP_RULES.md} A4 and E2).
 */
@Transactional
class RecentTransactionsQueryTest extends AbstractPersistenceTest {

    @Autowired EntityManager em;
    @Autowired CustomerRecords records;

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    private Customer customer(String ref) {
        Customer c = new Customer();
        c.setExternalRef(ref + "-" + UUID.randomUUID().toString().substring(0, 8));
        c.setFirstName("Txn");
        c.setLastName("Owner");
        c.setRegion("north");
        em.persist(c);
        return c;
    }

    private Account account(Customer owner) {
        BankingProduct product = em.createQuery(
                        "select p from BankingProduct p where p.code = 'CUR_ESSENTIAL'", BankingProduct.class)
                .getSingleResult();
        Account a = new Account();
        a.setCustomer(owner);
        a.setProduct(product);
        a.setIban("FR76" + UUID.randomUUID().toString().replace("-", "").substring(0, 23).toUpperCase());
        a.setStatus(AccountStatus.ACTIVE);
        a.setOpenedAt(LocalDate.of(2024, 1, 1));
        em.persist(a);
        return a;
    }

    private void movement(Account account, String label, long minutesAfterT0) {
        BankTransaction t = new BankTransaction();
        t.setAccount(account);
        t.setType(TransactionType.CARD_PAYMENT);
        t.setAmount(new BigDecimal("-10.00"));
        t.setLabel(label);
        t.setStatus(TransactionStatus.BOOKED);
        t.setBookedAt(T0.plusSeconds(minutesAfterT0 * 60));
        em.persist(t);
    }

    @Test
    @DisplayName("every account of the customer, newest first, and nobody else's movements")
    void ownMovementsOnlyNewestFirst() {
        Customer owner = customer("OWN");
        Account current = account(owner);
        Account savings = account(owner);
        Customer stranger = customer("OTHER");
        Account strangers = account(stranger);

        movement(current, "oldest", 1);
        movement(savings, "middle", 2);
        movement(current, "newest", 3);
        movement(strangers, "stranger newest of all", 10);
        em.flush();
        em.clear();

        assertThat(records.findRecentTransactions(owner.getId(), 10))
                .extracting(TransactionSummary::label)
                .containsExactly("newest", "middle", "oldest");
    }

    @Test
    @DisplayName("the limit keeps the newest movements, not arbitrary ones")
    void limitKeepsTheNewest() {
        Customer owner = customer("LIM");
        Account current = account(owner);
        movement(current, "first", 1);
        movement(current, "second", 2);
        movement(current, "third", 3);
        em.flush();
        em.clear();

        assertThat(records.findRecentTransactions(owner.getId(), 2))
                .extracting(TransactionSummary::label)
                .containsExactly("third", "second");
    }
}
