package com.callverse.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.entities.BankTransaction;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.ServiceIncident;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.persistence.AbstractPersistenceTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The demo dataset behind the advisor-workspace scenario: Amina Haddad's declined card, Friday
 * evening in Marseille. Loaded only in {@code dev} and only when explicitly enabled, and safe to run
 * on every boot.
 */
@ActiveProfiles("dev")
@TestPropertySource(properties = "callverse.demo-data.enabled=true")
class DemoDataLoaderTest extends AbstractPersistenceTest {

    @Autowired EntityManager em;
    @Autowired ApplicationContext context;

    private List<Customer> amina() {
        return em.createQuery("select c from Customer c where c.externalRef = 'DEMO-00418'", Customer.class)
                .getResultList();
    }

    @Test
    @DisplayName("the scenario's customer, card, transactions, conversation and articles are present")
    void scenarioDataIsPresent() {
        assertThat(amina()).hasSize(1);
        Customer customer = amina().get(0);
        assertThat(customer.getRegion()).isEqualTo("Marseille");

        List<Card> cards = em.createQuery(
                        "select k from Card k where k.account.customer.id = :id", Card.class)
                .setParameter("id", customer.getId())
                .getResultList();
        assertThat(cards).extracting(Card::getStatus).contains(CardStatus.ACTIVE);

        List<BankTransaction> movements = em.createQuery(
                        "select t from BankTransaction t where t.account.customer.id = :id", BankTransaction.class)
                .setParameter("id", customer.getId())
                .getResultList();
        assertThat(movements).extracting(BankTransaction::getStatus)
                .contains(TransactionStatus.REJECTED, TransactionStatus.PENDING);

        List<Conversation> conversations = em.createQuery(
                        "select c from Conversation c where c.customer.id = :id", Conversation.class)
                .setParameter("id", customer.getId())
                .getResultList();
        assertThat(conversations).extracting(Conversation::getStatus).contains(ConversationStatus.ACTIVE);

        Long articles = em.createQuery(
                        "select count(a) from KbArticle a where a.published = true and a.title like 'Faire opposition%'",
                        Long.class)
                .getSingleResult();
        assertThat(articles).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Marseille has no live outage (path B of the scenario); Lyon has one")
    void outagesMatchTheScenario() {
        List<ServiceIncident> live = em.createQuery(
                        "select i from ServiceIncident i where i.resolvedAt is null and i.runId is null"
                                + " and (i.region in ('Marseille', 'Lyon') or i.region is null)",
                        ServiceIncident.class)
                .getResultList();
        assertThat(live).extracting(ServiceIncident::getRegion)
                .contains("Lyon")
                .doesNotContain("Marseille")
                .as("a national outage would reach Marseille too")
                .doesNotContainNull();
    }

    @Test
    @DisplayName("the dev advisor login is Karim, holding the four skills, and Amina's call is his; Amina is the dev customer login")
    void staffingMatchesTheScenario() {
        List<Advisor> karim = em.createQuery(
                        "select a from Advisor a where a.user.email = 'advisor@callverse.local'", Advisor.class)
                .getResultList();
        assertThat(karim).hasSize(1);
        List<String> skills = em.createQuery(
                        "select s.skill.code from AdvisorSkill s where s.advisor.id = :id", String.class)
                .setParameter("id", karim.get(0).getId())
                .getResultList();
        assertThat(skills).containsExactlyInAnyOrder("ACCOUNTS", "CARDS", "CREDIT", "FRAUD");

        Conversation call = em.createQuery(
                        "select c from Conversation c where c.customer.externalRef = 'DEMO-00418'", Conversation.class)
                .getSingleResult();
        assertThat(call.getAdvisor().getId()).isEqualTo(karim.get(0).getId());
        assertThat(call.getAssignedAt()).isNotNull();

        String aminaLogin = em.createQuery(
                        "select c.user.email from Customer c where c.externalRef = 'DEMO-00418'", String.class)
                .getSingleResult();
        assertThat(aminaLogin).isEqualTo("customer@callverse.local");

        Long waiting = em.createQuery(
                        "select count(c) from Conversation c where c.customer.externalRef like 'DEMO-%'"
                                + " and c.status = com.callverse.core.domain.enums.ConversationStatus.QUEUED",
                        Long.class)
                .getSingleResult();
        assertThat(waiting).as("contacts waiting, so an advisor can take one live").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("running the loader again adds nothing")
    void loaderIsIdempotent() throws Exception {
        ApplicationArguments none = new DefaultApplicationArguments();
        for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) {
            if (runner.getClass().getName().contains("DemoDataLoader")) {
                runner.run(none);
            }
        }
        em.clear();
        assertThat(amina()).hasSize(1);
        assertThat(em.createQuery(
                        "select count(a) from Advisor a where a.user.email = 'advisor@callverse.local'", Long.class)
                .getSingleResult()).isEqualTo(1);
    }
}
