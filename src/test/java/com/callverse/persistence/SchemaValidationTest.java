package com.callverse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.Contract;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Invoice;
import com.callverse.core.domain.entities.Plan;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ContractStatus;
import com.callverse.core.domain.enums.InvoiceStatus;
import com.callverse.core.domain.enums.UserRole;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves that {@code V1__init.sql} and the JPA entities agree, against a real PostgreSQL.
 *
 * <p><strong>What makes this test meaningful.</strong> The application runs with
 * {@code ddl-auto: validate}, so Hibernate compares every mapped column against the live schema at
 * startup. Until entities existed, that check passed by comparing nothing — a green light that
 * proved only that there was nothing to check. Every test here depends on the Spring context having
 * started, which means Flyway applied both migrations and validation passed against real tables.
 *
 * <p>Validation is directional and it is worth knowing which way: Hibernate checks that everything
 * the entities map exists in the database. It does <em>not</em> check the reverse, so a column
 * present in the migration and absent from the entity — {@code kb_chunk.embedding}, deliberately —
 * is invisible to it.
 */
@Transactional
class SchemaValidationTest extends AbstractPersistenceTest {

    @Autowired EntityManager em;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("the context starts, so Flyway ran and ddl-auto=validate accepted every entity")
    void schemaValidates() {
        // Reaching this line is the assertion. Kept explicit so a failure reads as a schema
        // disagreement rather than as an unexplained context-loading error.
        assertThat(em).isNotNull();
    }

    @Nested
    @DisplayName("Block 1-2 — identity and customer domain")
    class IdentityAndCustomer {

        @Test
        @DisplayName("a customer, its contract and that contract's invoice round-trip")
        void customerAggregateRoundTrips() {
            AppUser user = new AppUser();
            user.setEmail(unique("round.trip") + "@callverse.local");
            user.setPasswordHash("$2a$10$notarealhashjustfortestingpurposesonly000000000000000000");
            user.setFirstName("Round");
            user.setLastName("Trip");
            user.setRole(UserRole.CUSTOMER);
            em.persist(user);

            Customer customer = new Customer();
            customer.setUser(user);
            customer.setExternalRef(unique("CUST"));
            customer.setFirstName("Round");
            customer.setLastName("Trip");
            customer.setZone("north");
            customer.setChurnRisk(ChurnRisk.HIGH);
            customer.setTenureMonths(18);
            em.persist(customer);

            Plan plan = em.createQuery("select p from Plan p where p.code = :c", Plan.class)
                    .setParameter("c", "FIB_1G")
                    .getSingleResult();

            Contract contract = new Contract();
            contract.setCustomer(customer);
            contract.setPlan(plan);
            contract.setStatus(ContractStatus.ACTIVE);
            contract.setStartedAt(LocalDate.of(2025, 1, 15));
            em.persist(contract);

            Invoice invoice = new Invoice();
            invoice.setContract(contract);
            invoice.setPeriodStart(LocalDate.of(2025, 1, 1));
            invoice.setPeriodEnd(LocalDate.of(2025, 1, 31));
            invoice.setAmount(new BigDecimal("44.99"));
            invoice.setStatus(InvoiceStatus.DISPUTED);
            em.persist(invoice);

            em.flush();
            em.clear();

            // Traverse the whole chain back: invoice -> contract -> customer -> user, plus the
            // aggregate's own collection in the other direction.
            Invoice loaded = em.find(Invoice.class, invoice.getId());
            assertThat(loaded.getAmount()).isEqualByComparingTo("44.99");
            assertThat(loaded.getStatus()).isEqualTo(InvoiceStatus.DISPUTED);
            assertThat(loaded.getContract().getPlan().getCode()).isEqualTo("FIB_1G");
            assertThat(loaded.getContract().getCustomer().getChurnRisk()).isEqualTo(ChurnRisk.HIGH);
            assertThat(loaded.getContract().getCustomer().getUser().getRole())
                    .isEqualTo(UserRole.CUSTOMER);
            assertThat(loaded.getContract().getCustomer().getContracts())
                    .extracting(Contract::getId)
                    .contains(contract.getId());
        }

        @Test
        @DisplayName("a simulated customer needs no account, which is what lets both modes coexist")
        void simulatedCustomerHasNoUser() {
            Customer simulated = new Customer();
            simulated.setExternalRef(unique("SIM"));
            simulated.setFirstName("Synthetic");
            simulated.setLastName("Customer");
            simulated.setZone("south");
            simulated.setSimulated(true);
            em.persist(simulated);
            em.flush();
            em.clear();

            Customer loaded = em.find(Customer.class, simulated.getId());
            assertThat(loaded.getUser()).isNull();
            assertThat(loaded.isSimulated()).isTrue();
            assertThat(loaded.getChurnRisk()).isEqualTo(ChurnRisk.LOW);
        }

        @Test
        @DisplayName("BigDecimal money keeps its scale through a round trip")
        void moneyKeepsScale() {
            Plan plan = em.createQuery("select p from Plan p where p.code = :c", Plan.class)
                    .setParameter("c", "MOB_ESSENTIAL")
                    .getSingleResult();
            // NUMERIC(8,2): the value must come back as stored, not as a binary-float approximation.
            assertThat(plan.getMonthlyPrice()).isEqualByComparingTo("19.99");
            assertThat(plan.getMonthlyPrice().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("V2 reference seed")
    class ReferenceSeed {

        @Test
        @DisplayName("every reference table is seeded with the documented rows")
        void seedCountsAreCorrect() {
            assertThat(count("Plan")).isEqualTo(5);
            // Native counts for tables whose entities arrive in a later block; replaced with JPQL
            // as those entities land.
            assertThat(nativeCount("skill")).isEqualTo(3);
            assertThat(nativeCount("sla_policy")).isEqualTo(3);
            assertThat(nativeCount("quality_criterion")).isEqualTo(6);
            assertThat(nativeCount("control_strategy")).isEqualTo(3);
            assertThat(nativeCount("app_user")).isGreaterThanOrEqualTo(4);
        }

        private long nativeCount(String table) {
            return ((Number) em.createNativeQuery("select count(*) from " + table)
                    .getSingleResult())
                    .longValue();
        }

        @Test
        @DisplayName("quality criterion weights sum to exactly 1.000")
        void qualityWeightsSumToOne() {
            BigDecimal sum = (BigDecimal) em.createNativeQuery(
                            "select sum(weight) from quality_criterion")
                    .getSingleResult();
            // Exactly 1.000, not approximately: a grid whose weights do not sum to 1 produces a
            // global score that cannot be compared across conversations.
            assertThat(sum).isEqualByComparingTo("1.000");
        }

        @Test
        @DisplayName("one test account exists per role")
        void oneAccountPerRole() {
            for (UserRole role : UserRole.values()) {
                Long n = em.createQuery(
                                "select count(u) from AppUser u where u.role = :r", Long.class)
                        .setParameter("r", role)
                        .getSingleResult();
                assertThat(n).as("seeded accounts for %s", role).isGreaterThanOrEqualTo(1);
            }
        }

        private long count(String entity) {
            return em.createQuery("select count(e) from " + entity + " e", Long.class)
                    .getSingleResult();
        }
    }
}
