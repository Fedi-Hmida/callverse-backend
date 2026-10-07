package com.callverse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.domain.entities.ControlStrategy;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.entities.SimulationRun;
import com.callverse.core.domain.enums.LoadProfile;
import com.callverse.core.domain.enums.RunStatus;
import com.callverse.core.domain.enums.StrategyKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the database actually rejects what the schema says it rejects.
 *
 * <p>Separate from {@link SchemaValidationTest} because each test here deliberately poisons its
 * transaction: a constraint violation marks it rollback-only, so no assertion can follow inside the
 * same one. One violation per test method, one transaction each.
 *
 * <p><strong>Why this exists.</strong> A CHECK constraint that has never rejected anything and a
 * UNIQUE constraint that has never collided are indistinguishable from constraints that were
 * silently dropped. Both of the ones below carry real weight — {@code uq_run} is the guarantee
 * behind reproducible experiments — so both are challenged rather than assumed.
 */
@Transactional
class ConstraintEnforcementTest extends AbstractPersistenceTest {

    @Autowired EntityManager em;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("conversation.status rejects a value outside the CHECK constraint")
    void invalidConversationStatusIsRejected() {
        Customer customer = new Customer();
        customer.setExternalRef("CHK-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Check");
        customer.setLastName("Constraint");
        customer.setRegion("north");
        em.persist(customer);
        em.flush();

        // Native SQL on purpose: the JPA enum mapping makes an invalid status unrepresentable in
        // Java, so going through the entity would test the type system rather than the database.
        // This is the path a data import or a hand-written migration would actually take.
        assertThatThrownBy(() -> {
                    em.createNativeQuery(
                                    """
                                    insert into conversation (id, customer_id, status, channel, priority_score, queued_at)
                                    values (gen_random_uuid(), :cid, 'NOT_A_REAL_STATUS', 'CHAT', 0, now())
                                    """)
                            .setParameter("cid", customer.getId())
                            .executeUpdate();
                    em.flush();
                })
            .hasStackTraceContaining("conversation_status_check");
    }

    @Test
    @DisplayName("uq_run rejects a duplicate (scenario, strategy, seed) triple")
    void duplicateRunTripleIsRejected() throws Exception {
        Scenario scenario = new Scenario();
        scenario.setName("Saturation " + UUID.randomUUID().toString().substring(0, 8));
        scenario.setLoadProfile(LoadProfile.SATURATED);
        scenario.setDurationMinutes(60);
        scenario.setAdvisorCount(12);
        scenario.setSkillDistribution(objectMapper.readTree(
                "{\"CARDS\":0.4,\"ACCOUNTS\":0.3,\"CREDIT\":0.2,\"FRAUD\":0.1}"));
        scenario.setCustomerProfileMix(objectMapper.readTree(
                "{\"LOW\":0.7,\"MEDIUM\":0.2,\"HIGH\":0.1}"));
        em.persist(scenario);

        ControlStrategy strategy = new ControlStrategy();
        strategy.setCode("DUP-" + UUID.randomUUID().toString().substring(0, 8));
        strategy.setName("Duplicate probe");
        strategy.setKind(StrategyKind.BASELINE);
        em.persist(strategy);

        SimulationRun first = new SimulationRun();
        first.setScenario(scenario);
        first.setStrategy(strategy);
        first.setSeed(42L);
        first.setStatus(RunStatus.COMPLETED);
        em.persist(first);
        em.flush();

        SimulationRun duplicate = new SimulationRun();
        duplicate.setScenario(scenario);
        duplicate.setStrategy(strategy);
        duplicate.setSeed(42L); // same triple
        duplicate.setStatus(RunStatus.PENDING);

        // This is the constraint that makes the baseline-versus-RL comparison defensible: the same
        // configuration cannot produce a second, divergent row for someone to average in later.
        assertThatThrownBy(() -> {
                    em.persist(duplicate);
                    em.flush();
                })
            .hasStackTraceContaining("uq_run");
    }

    @Test
    @DisplayName("chk_account_dates rejects a closing date before the opening date")
    void accountClosedBeforeOpenedIsRejected() {
        UUID customerId = persistCustomer("DATE");
        UUID productId = productId();

        assertThatThrownBy(() -> {
                    em.createNativeQuery(
                                    """
                                    insert into account (id, customer_id, product_id, iban, status, opened_at, closed_at)
                                    values (gen_random_uuid(), :cid, :pid, :iban, 'CLOSED', date '2025-06-01', date '2025-01-01')
                                    """)
                            .setParameter("cid", customerId)
                            .setParameter("pid", productId)
                            .setParameter("iban", uniqueIban())
                            .executeUpdate();
                    em.flush();
                })
            .hasStackTraceContaining("chk_account_dates");
    }

    @Test
    @DisplayName("card.pan_last4 refuses anything but four digits")
    void nonDigitLastFourIsRejected() {
        UUID accountId = persistAccount();

        // PCI-DSS: the schema itself makes a stored PAN impossible, whatever the application does.
        // A longer value is already refused by VARCHAR(4) before any CHECK runs, so this uses four
        // characters that are not all digits: only the named CHECK can refuse it, and the assertion
        // names the constraint rather than a column that also appears in the SQL text.
        assertThatThrownBy(() -> {
                    em.createNativeQuery(
                                    """
                                    insert into card (id, account_id, pan_last4, network, type, status, expires_on, daily_limit)
                                    values (gen_random_uuid(), :aid, '12a4', 'VISA', 'DEBIT', 'ACTIVE', date '2029-12-31', 500)
                                    """)
                            .setParameter("aid", accountId)
                            .executeUpdate();
                    em.flush();
                })
            .hasStackTraceContaining("card_pan_last4_check");
    }

    @Test
    @DisplayName("chk_card_blocked refuses a blocked card that says neither when nor why")
    void blockedCardWithoutReasonIsRejected() {
        UUID accountId = persistAccount();

        assertThatThrownBy(() -> {
                    em.createNativeQuery(
                                    """
                                    insert into card (id, account_id, pan_last4, network, type, status, expires_on, daily_limit)
                                    values (gen_random_uuid(), :aid, '4242', 'VISA', 'DEBIT', 'BLOCKED', date '2029-12-31', 500)
                                    """)
                            .setParameter("aid", accountId)
                            .executeUpdate();
                    em.flush();
                })
            .hasStackTraceContaining("chk_card_blocked");
    }

    private UUID persistCustomer(String prefix) {
        Customer customer = new Customer();
        customer.setExternalRef(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Check");
        customer.setLastName("Constraint");
        customer.setRegion("west");
        em.persist(customer);
        em.flush();
        return customer.getId();
    }

    private UUID productId() {
        return (UUID) em.createNativeQuery("select id from banking_product where code = 'CUR_ESSENTIAL'")
                .getSingleResult();
    }

    private UUID persistAccount() {
        UUID customerId = persistCustomer("CARD");
        UUID accountId = UUID.randomUUID();
        em.createNativeQuery(
                        """
                        insert into account (id, customer_id, product_id, iban, status, opened_at)
                        values (:id, :cid, :pid, :iban, 'ACTIVE', date '2024-01-01')
                        """)
                .setParameter("id", accountId)
                .setParameter("cid", customerId)
                .setParameter("pid", productId())
                .setParameter("iban", uniqueIban())
                .executeUpdate();
        em.flush();
        return accountId;
    }

    private static String uniqueIban() {
        return "FR76" + UUID.randomUUID().toString().replace("-", "").substring(0, 23).toUpperCase();
    }

    @Test
    @DisplayName("metric_sample rejects a null skill_id, which the primary key forces NOT NULL")
    void metricSampleRejectsNullSkill() throws Exception {
        Scenario scenario = new Scenario();
        scenario.setName("PK " + UUID.randomUUID().toString().substring(0, 8));
        scenario.setLoadProfile(LoadProfile.LOW);
        scenario.setDurationMinutes(10);
        scenario.setAdvisorCount(2);
        scenario.setSkillDistribution(objectMapper.readTree("{\"CARDS\":1.0}"));
        scenario.setCustomerProfileMix(objectMapper.readTree("{\"LOW\":1.0}"));
        em.persist(scenario);

        ControlStrategy strategy = new ControlStrategy();
        strategy.setCode("PK-" + UUID.randomUUID().toString().substring(0, 8));
        strategy.setName("PK probe");
        strategy.setKind(StrategyKind.BASELINE);
        em.persist(strategy);

        SimulationRun run = new SimulationRun();
        run.setScenario(scenario);
        run.setStrategy(strategy);
        run.setSeed(7L);
        run.setStatus(RunStatus.RUNNING);
        em.persist(run);
        em.flush();

        // Documents the schema finding raised with its author: skill_id is declared as a nullable
        // foreign key but sits in the primary key, and PostgreSQL forces PK columns NOT NULL. A
        // cross-skill aggregate sample is therefore not representable.
        assertThatThrownBy(() -> {
                    em.createNativeQuery(
                                    """
                                    insert into metric_sample (run_id, sim_time, skill_id, queue_length)
                                    values (:rid, 0, null, 5)
                                    """)
                            .setParameter("rid", run.getId())
                            .executeUpdate();
                    em.flush();
                })
            .hasStackTraceContaining("skill_id");
    }

    @Test
    @DisplayName("commercial_credit rejects a non-positive amount")
    void nonPositiveCreditIsRejected() {
        Customer customer = new Customer();
        customer.setExternalRef("AMT-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Amount");
        customer.setLastName("Constraint");
        customer.setRegion("south");
        em.persist(customer);
        em.flush();

        // A negative credit is a charge. Charging a customer through the goodwill path would be a
        // serious bug, so the database makes it unrepresentable rather than trusting the caller.
        assertThatThrownBy(() -> {
                    em.createNativeQuery(
                                    """
                                    insert into commercial_credit (id, customer_id, amount, reason, created_at)
                                    values (gen_random_uuid(), :cid, -10.00, 'should not be possible', now())
                                    """)
                            .setParameter("cid", customer.getId())
                            .executeUpdate();
                    em.flush();
                })
            .hasStackTraceContaining("commercial_credit_amount_check");
    }

    @Test
    @DisplayName("app_user rejects a duplicate email")
    void duplicateEmailIsRejected() {
        String email = "dup-" + UUID.randomUUID().toString().substring(0, 8) + "@callverse.local";
        for (int i = 0; i < 1; i++) {
            em.createNativeQuery(
                            """
                            insert into app_user (id, email, password_hash, first_name, last_name, role, active, created_at)
                            values (gen_random_uuid(), :email, 'x', 'A', 'B', 'CUSTOMER', true, now())
                            """)
                    .setParameter("email", email)
                    .executeUpdate();
        }
        em.flush();

        assertThatThrownBy(() -> {
                    em.createNativeQuery(
                                    """
                                    insert into app_user (id, email, password_hash, first_name, last_name, role, active, created_at)
                                    values (gen_random_uuid(), :email, 'x', 'C', 'D', 'ADMIN', true, now())
                                    """)
                            .setParameter("email", email)
                            .executeUpdate();
                    em.flush();
                })
            .hasStackTraceContaining("app_user_email_key");
    }

    @Test
    @DisplayName("the queue query uses idx_conv_status_queue rather than scanning the table")
    void queueQueryUsesItsIndex() {
        // The index exists to serve exactly one query, executed every time an advisor frees up.
        // Asserting the plan rather than the result is the only way to notice if a later change to
        // the query, or to the index's column order, quietly turns it into a sequential scan.
        List<?> rows = em.createNativeQuery(
                        """
                        explain
                        select * from conversation
                         where status = 'QUEUED' and skill_id = (select id from skill limit 1)
                         order by priority_score desc
                         limit 1
                        """)
                .getResultList();
        String plan = rows.stream().map(String::valueOf).collect(Collectors.joining("\n"));

        assertThat(plan).as("query plan:%s", plan).contains("idx_conv_status_queue");
    }
}
