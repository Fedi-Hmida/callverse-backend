package com.callverse.conversation;

import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.entities.AdvisorSkill;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Escalation;
import com.callverse.core.domain.entities.Skill;
import com.callverse.core.domain.entities.SlaPolicy;
import com.callverse.core.domain.enums.AdvisorStatus;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.UserRole;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Committed rows for the conversation core's tests. Each method runs in its own transaction, so the
 * adapters under test — which open their own — see the rows, and concurrent tests can race on them.
 *
 * <p><strong>Every test makes its own skill.</strong> Queues are per skill and the container is
 * shared by the whole suite, so a test that used CARDS would see every other test's leftovers. A
 * fresh skill code is a private queue. Its code starts with {@link #TEST_SKILL_PREFIX}, so that the
 * reference-seed test can tell the migration's skills from these.
 */
public final class ConversationFixtures {

    /** Every skill a test creates starts with this; no migration ever seeds such a code. */
    public static final String TEST_SKILL_PREFIX = "ZZT_";

    private final EntityManager em;
    private final TransactionTemplate tx;

    public ConversationFixtures(EntityManager em, TransactionTemplate tx) {
        this.em = em;
        this.tx = tx;
    }

    public static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** A new skill with its own SLA policy, so its queue is private to the calling test. */
    public String skill(int slaTargetSeconds) {
        return tx.execute(status -> {
            Skill skill = new Skill();
            skill.setCode(TEST_SKILL_PREFIX + suffix());
            skill.setLabel("Test skill");
            em.persist(skill);
            SlaPolicy policy = new SlaPolicy();
            policy.setSkill(skill);
            policy.setTargetSeconds(slaTargetSeconds);
            policy.setTargetRatio(new BigDecimal("0.800"));
            em.persist(policy);
            return skill.getCode();
        });
    }

    /** A second active policy on the same skill: the S-5 ambiguity. */
    public void extraPolicy(String skillCode, int targetSeconds) {
        tx.executeWithoutResult(status -> {
            SlaPolicy policy = new SlaPolicy();
            policy.setSkill(skillByCode(skillCode));
            policy.setTargetSeconds(targetSeconds);
            policy.setTargetRatio(new BigDecimal("0.900"));
            em.persist(policy);
        });
    }

    public UUID user(UserRole role) {
        return tx.execute(status -> {
            AppUser user = new AppUser();
            user.setEmail(role.name().toLowerCase() + "-" + suffix() + "@test.local");
            user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuuJ4H1y9oD6kBz1V5Q2yQ1w5bLb6xXyZ2");
            user.setFirstName("Test");
            user.setLastName(role.name());
            user.setRole(role);
            em.persist(user);
            return user.getId();
        });
    }

    /** An advisor linked to {@code userId}, holding each skill at level 2. */
    public UUID advisor(UUID userId, int maxConcurrent, String... skillCodes) {
        return tx.execute(status -> {
            Advisor advisor = new Advisor();
            advisor.setUser(userId == null ? null : em.getReference(AppUser.class, userId));
            advisor.setDisplayName("Karim " + suffix());
            advisor.setStatus(AdvisorStatus.AVAILABLE);
            advisor.setMaxConcurrent(maxConcurrent);
            em.persist(advisor);
            for (String code : skillCodes) {
                AdvisorSkill held = new AdvisorSkill();
                held.setAdvisor(advisor);
                held.setSkill(skillByCode(code));
                held.setLevel((short) 2);
                em.persist(held);
            }
            return advisor.getId();
        });
    }

    public UUID customer(UUID userId, ChurnRisk risk, CustomerSegment segment) {
        return tx.execute(status -> {
            Customer customer = new Customer();
            customer.setExternalRef("CONV-" + suffix());
            customer.setFirstName("Amina");
            customer.setLastName("Haddad");
            customer.setRegion("Marseille");
            customer.setChurnRisk(risk);
            customer.setSegment(segment);
            customer.setUser(userId == null ? null : em.getReference(AppUser.class, userId));
            em.persist(customer);
            return customer.getId();
        });
    }

    public UUID customer() {
        return customer(null, ChurnRisk.LOW, CustomerSegment.MASS);
    }

    /** A live conversation in {@code status}, queued at {@code queuedAt}, scored {@code priority}. */
    public UUID conversation(UUID customerId, String skillCode, ConversationStatus status, String priority, Instant queuedAt) {
        return conversation(customerId, skillCode, status, priority, queuedAt, null, null);
    }

    public UUID conversation(UUID customerId, String skillCode, ConversationStatus status, String priority,
            Instant queuedAt, UUID advisorId, UUID runId) {
        return tx.execute(s -> {
            Conversation c = new Conversation();
            c.setCustomer(em.getReference(Customer.class, customerId));
            c.setSkill(skillByCode(skillCode));
            c.setIntent(Intent.OTHER);
            c.setStatus(status);
            c.setPriorityScore(new BigDecimal(priority));
            c.setQueuedAt(queuedAt.truncatedTo(ChronoUnit.MICROS));
            c.setRunId(runId);
            if (advisorId != null) {
                c.setAdvisor(em.getReference(Advisor.class, advisorId));
                c.setAssignedAt(queuedAt.plusSeconds(10).truncatedTo(ChronoUnit.MICROS));
            }
            em.persist(c);
            return c.getId();
        });
    }

    public UUID pendingEscalation(UUID conversationId) {
        return tx.execute(s -> {
            Escalation e = new Escalation();
            e.setConversation(em.getReference(Conversation.class, conversationId));
            e.setReason("Fraude carte suspectee");
            e.setRaisedBy(EscalationRaisedBy.ADVISOR);
            e.setStatus(EscalationStatus.PENDING);
            em.persist(e);
            return e.getId();
        });
    }

    /** The row as the database holds it now, outside any test transaction. */
    public Conversation reload(UUID conversationId) {
        return tx.execute(s -> {
            Conversation c = em.find(Conversation.class, conversationId);
            em.detach(c);
            return c;
        });
    }

    public Escalation reloadEscalation(UUID escalationId) {
        return tx.execute(s -> {
            Escalation e = em.createQuery(
                            "select e from Escalation e left join fetch e.resolvedBy where e.id = :id", Escalation.class)
                    .setParameter("id", escalationId)
                    .getSingleResult();
            em.detach(e);
            return e;
        });
    }

    public UUID advisorOf(UUID conversationId) {
        return tx.execute(s -> em.createQuery(
                        "select c.advisor.id from Conversation c where c.id = :id", UUID.class)
                .setParameter("id", conversationId)
                .getSingleResult());
    }

    private Skill skillByCode(String code) {
        return em.createQuery("select s from Skill s where s.code = :c", Skill.class)
                .setParameter("c", code)
                .getSingleResult();
    }
}
