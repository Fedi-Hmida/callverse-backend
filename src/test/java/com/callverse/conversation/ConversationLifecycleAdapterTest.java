package com.callverse.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.AdvisorDirectory;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.ConversationMessages.PostedMessage;
import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import com.callverse.core.application.interfaces.LiveOperations;
import com.callverse.core.application.interfaces.LiveOperations.QueueDepth;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Escalation;
import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.EscalationStatus;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.exceptions.AdvisorUnavailableException;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import com.callverse.persistence.AbstractPersistenceTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The conversation core's persistence, against a real PostgreSQL: locks, {@code SKIP LOCKED}, the
 * denormalized metrics and the live figures. No test transaction wraps these tests — the adapters
 * open and commit their own, exactly as in production, and every assertion re-reads the committed row.
 */
class ConversationLifecycleAdapterTest extends AbstractPersistenceTest {

    @Autowired ConversationLifecycle lifecycle;
    @Autowired ConversationMessages messages;
    @Autowired LiveOperations operations;
    @Autowired AdvisorDirectory advisors;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private ConversationFixtures fx;
    private final Instant t0 = Instant.parse("2026-10-02T07:00:00Z");

    @BeforeEach
    void setUp() {
        fx = new ConversationFixtures(em, tx);
    }

    @Nested
    @DisplayName("open")
    class Open {

        @Test
        @DisplayName("queues a live contact, scored from the customer and the intent, stamped with now")
        void opens() {
            String skill = fx.skill(30);
            UUID amina = fx.customer(null, ChurnRisk.MEDIUM, CustomerSegment.AFFLUENT);

            ConversationRecord r = lifecycle.open(amina, skill, Intent.FRAUD, Channel.CHAT, t0);

            Conversation stored = fx.reload(r.id());
            assertThat(stored.getStatus()).isEqualTo(ConversationStatus.QUEUED);
            assertThat(stored.getPriorityScore()).isEqualByComparingTo("65"); // 20 + 15 + 30
            assertThat(stored.getQueuedAt()).isEqualTo(t0);
            assertThat(stored.getRunId()).isNull();
            assertThat(stored.getAdvisor()).isNull();
            assertThat(r.skill()).isEqualTo(skill);
            assertThat(r.customerId()).isEqualTo(amina);
        }

        @Test
        @DisplayName("an unknown customer or skill is RESOURCE_NOT_FOUND, and nothing is stored")
        void unknown() {
            String skill = fx.skill(60);
            assertThatThrownBy(() -> lifecycle.open(UUID.randomUUID(), skill, Intent.CARD, Channel.CHAT, t0))
                    .isInstanceOf(ResourceNotFoundException.class);
            UUID customer = fx.customer();
            assertThatThrownBy(() -> lifecycle.open(customer, "NOPE", Intent.CARD, Channel.CHAT, t0))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("assignNext")
    class AssignNext {

        @Test
        @DisplayName("takes the highest score, then the oldest; records wait and SLA; leaves other queues alone")
        void takesTheHead() {
            String skill = fx.skill(60);
            String otherSkill = fx.skill(60);
            UUID customer = fx.customer();
            UUID low = fx.conversation(customer, skill, ConversationStatus.QUEUED, "10", t0);
            UUID highNewer = fx.conversation(customer, skill, ConversationStatus.QUEUED, "55", t0.plusSeconds(20));
            UUID highOlder = fx.conversation(customer, skill, ConversationStatus.QUEUED, "55", t0.plusSeconds(5));
            UUID elsewhere = fx.conversation(customer, otherSkill, ConversationStatus.QUEUED, "95", t0);
            UUID simulated = fx.conversation(customer, skill, ConversationStatus.QUEUED, "99", t0, null, UUID.randomUUID());
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 3, skill);

            ConversationRecord first = lifecycle.assignNext(advisor, skill, t0.plusSeconds(47)).orElseThrow();

            assertThat(first.id()).isEqualTo(highOlder);
            Conversation stored = fx.reload(highOlder);
            assertThat(stored.getStatus()).isEqualTo(ConversationStatus.ASSIGNED);
            assertThat(stored.getAssignedAt()).isEqualTo(t0.plusSeconds(47));
            assertThat(stored.getWaitSeconds()).isEqualTo(42);
            assertThat(stored.getSlaMet()).isTrue();
            assertThat(fx.advisorOf(highOlder)).isEqualTo(advisor);

            assertThat(lifecycle.assignNext(advisor, skill, t0.plusSeconds(100)).orElseThrow().id()).isEqualTo(highNewer);
            ConversationRecord third = lifecycle.assignNext(advisor, skill, t0.plusSeconds(100)).orElseThrow();
            assertThat(third.id()).isEqualTo(low);
            assertThat(fx.reload(low).getSlaMet()).as("100 s against 60 s").isFalse();

            assertThat(fx.reload(elsewhere).getStatus()).isEqualTo(ConversationStatus.QUEUED);
            assertThat(fx.reload(simulated).getStatus()).as("a simulation run never meets a live advisor")
                    .isEqualTo(ConversationStatus.QUEUED);
        }

        @Test
        @DisplayName("an empty queue gives nothing and changes nothing")
        void empty() {
            String skill = fx.skill(60);
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);
            assertThat(lifecycle.assignNext(advisor, skill, t0)).isEmpty();
        }

        @Test
        @DisplayName("an advisor at max_concurrent is ADVISOR_UNAVAILABLE and the queue is untouched")
        void capacity() {
            String skill = fx.skill(60);
            UUID customer = fx.customer();
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);
            fx.conversation(customer, skill, ConversationStatus.ACTIVE, "0", t0, advisor, null);
            UUID waiting = fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", t0);

            assertThatThrownBy(() -> lifecycle.assignNext(advisor, skill, t0.plusSeconds(5)))
                    .isInstanceOf(AdvisorUnavailableException.class);
            assertThat(fx.reload(waiting).getStatus()).isEqualTo(ConversationStatus.QUEUED);
        }

        @Test
        @DisplayName("two active SLA policies on one skill: the strictest target is used (S-5), deterministically")
        void strictestPolicy() {
            String skill = fx.skill(60);
            fx.extraPolicy(skill, 30);
            UUID customer = fx.customer();
            UUID c = fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", t0);
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);

            lifecycle.assignNext(advisor, skill, t0.plusSeconds(45));

            assertThat(fx.reload(c).getSlaMet()).as("45 s meets 60 s but not 30 s").isFalse();
        }

        @Test
        @DisplayName("two advisors taking at the same moment get two different conversations")
        void raceBetweenAdvisors() throws Exception {
            String skill = fx.skill(60);
            UUID customer = fx.customer();
            UUID a = fx.conversation(customer, skill, ConversationStatus.QUEUED, "50", t0);
            UUID b = fx.conversation(customer, skill, ConversationStatus.QUEUED, "40", t0);
            UUID karim = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);
            UUID lina = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);

            List<Optional<ConversationRecord>> results = race(
                    () -> lifecycle.assignNext(karim, skill, t0.plusSeconds(10)),
                    () -> lifecycle.assignNext(lina, skill, t0.plusSeconds(10)));

            assertThat(results).allSatisfy(r -> assertThat(r).isPresent());
            assertThat(results.stream().map(r -> r.orElseThrow().id())).containsExactlyInAnyOrder(a, b);
            assertThat(fx.advisorOf(a)).isNotEqualTo(fx.advisorOf(b));
        }

        @Test
        @DisplayName("one advisor taking twice at once cannot exceed max_concurrent = 1")
        void raceWithinOneAdvisor() throws Exception {
            String skill = fx.skill(60);
            UUID customer = fx.customer();
            fx.conversation(customer, skill, ConversationStatus.QUEUED, "50", t0);
            fx.conversation(customer, skill, ConversationStatus.QUEUED, "40", t0);
            UUID karim = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);

            List<Object> outcomes = raceOutcomes(
                    () -> lifecycle.assignNext(karim, skill, t0.plusSeconds(10)),
                    () -> lifecycle.assignNext(karim, skill, t0.plusSeconds(10)));

            assertThat(outcomes).filteredOn(o -> o instanceof Optional<?>).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o instanceof AdvisorUnavailableException).hasSize(1);
            assertThat(lifecycle.heldBy(karim)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("close")
    class Close {

        @Test
        @DisplayName("resolving an ACTIVE conversation records the end and the handle time")
        void resolve() {
            String skill = fx.skill(60);
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.ACTIVE, "0", t0, advisor, null);

            ConversationRecord r = lifecycle.close(c, ConversationStatus.ACTIVE, ConversationStatus.RESOLVED, UUID.randomUUID(), t0.plusSeconds(370));

            Conversation stored = fx.reload(c);
            assertThat(r.status()).isEqualTo(ConversationStatus.RESOLVED);
            assertThat(stored.getStatus()).isEqualTo(ConversationStatus.RESOLVED);
            assertThat(stored.getEndedAt()).isEqualTo(t0.plusSeconds(370));
            assertThat(stored.getHandleSeconds()).isEqualTo(360); // assigned at t0 + 10 by the fixture
        }

        @Test
        @DisplayName("abandoning in the queue records how long the customer waited")
        void abandon() {
            String skill = fx.skill(60);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.QUEUED, "0", t0);

            lifecycle.close(c, ConversationStatus.QUEUED, ConversationStatus.ABANDONED, UUID.randomUUID(), t0.plusSeconds(95));

            Conversation stored = fx.reload(c);
            assertThat(stored.getStatus()).isEqualTo(ConversationStatus.ABANDONED);
            assertThat(stored.getWaitSeconds()).isEqualTo(95);
            assertThat(stored.getHandleSeconds()).isNull();
            assertThat(stored.getSlaMet()).as("95 s against 60 s: the customer gave up after the target").isFalse();
        }

        @Test
        @DisplayName("a customer who gives up within the target is neither a hit nor a miss")
        void abandonQuickly() {
            String skill = fx.skill(60);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.QUEUED, "0", t0);

            lifecycle.close(c, ConversationStatus.QUEUED, ConversationStatus.ABANDONED, UUID.randomUUID(), t0.plusSeconds(20));

            assertThat(fx.reload(c).getSlaMet()).isNull();
        }

        @Test
        @DisplayName("an escalation left pending on an ACTIVE conversation (raised before escalating moved it) is resolved with it")
        void legacyPendingEscalation() {
            String skill = fx.skill(60);
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.ACTIVE, "0", t0, advisor, null);
            UUID escalation = fx.pendingEscalation(c);

            lifecycle.close(c, ConversationStatus.ACTIVE, ConversationStatus.RESOLVED, UUID.randomUUID(), t0.plusSeconds(60));

            assertThat(fx.reloadEscalation(escalation).getStatus()).isEqualTo(EscalationStatus.RESOLVED);
        }

        @Test
        @DisplayName("resolving an ESCALATED conversation also resolves its pending escalation, naming who")
        void resolveEscalated() {
            String skill = fx.skill(60);
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.ESCALATED, "0", t0, advisor, null);
            UUID escalation = fx.pendingEscalation(c);
            UUID sarah = fx.user(UserRole.SUPERVISOR);

            lifecycle.close(c, ConversationStatus.ESCALATED, ConversationStatus.RESOLVED, sarah, t0.plusSeconds(600));

            Escalation stored = fx.reloadEscalation(escalation);
            assertThat(stored.getStatus()).isEqualTo(EscalationStatus.RESOLVED);
            assertThat(stored.getResolvedAt()).isEqualTo(t0.plusSeconds(600));
            assertThat(stored.getResolvedBy().getId()).isEqualTo(sarah);
        }

        @Test
        @DisplayName("an illegal close is INVALID_STATE_TRANSITION and the row is unchanged")
        void illegal() {
            String skill = fx.skill(60);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.QUEUED, "0", t0);

            assertThatThrownBy(() -> lifecycle.close(c, ConversationStatus.QUEUED, ConversationStatus.RESOLVED, UUID.randomUUID(), t0))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThat(fx.reload(c).getStatus()).isEqualTo(ConversationStatus.QUEUED);
        }

        @Test
        @DisplayName("a close decided on a status that has since changed is refused, and the row is unchanged")
        void staleDecision() {
            String skill = fx.skill(60);
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.ESCALATED, "0", t0, advisor, null);

            assertThatThrownBy(() -> lifecycle.close(
                            c, ConversationStatus.ACTIVE, ConversationStatus.RESOLVED, UUID.randomUUID(), t0))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThat(fx.reload(c).getStatus()).isEqualTo(ConversationStatus.ESCALATED);
        }

        @Test
        @DisplayName("a simulated conversation is not found by the live lifecycle")
        void simulatedNotFound() {
            String skill = fx.skill(60);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.QUEUED, "0", t0, null, UUID.randomUUID());
            assertThat(lifecycle.find(c)).isEmpty();
            assertThatThrownBy(() -> lifecycle.close(c, ConversationStatus.QUEUED, ConversationStatus.ABANDONED, UUID.randomUUID(), t0))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("find and heldBy")
    class Reads {

        @Test
        @DisplayName("find exposes the logins behind the customer and the advisor, for ownership checks")
        void find() {
            String skill = fx.skill(60);
            UUID karimUser = fx.user(UserRole.ADVISOR);
            UUID aminaUser = fx.user(UserRole.CUSTOMER);
            UUID advisor = fx.advisor(karimUser, 1, skill);
            UUID customer = fx.customer(aminaUser, ChurnRisk.LOW, CustomerSegment.MASS);
            UUID c = fx.conversation(customer, skill, ConversationStatus.ACTIVE, "0", t0, advisor, null);

            ConversationRecord r = lifecycle.find(c).orElseThrow();

            assertThat(r.customerUserId()).isEqualTo(aminaUser);
            assertThat(r.advisorUserId()).isEqualTo(karimUser);
            assertThat(r.advisorId()).isEqualTo(advisor);
            assertThat(r.skill()).isEqualTo(skill);
        }

        @Test
        @DisplayName("heldBy lists ASSIGNED, ACTIVE and ESCALATED, never closed ones or other advisors'")
        void heldBy() {
            String skill = fx.skill(60);
            UUID customer = fx.customer();
            UUID karim = fx.advisor(fx.user(UserRole.ADVISOR), 3, skill);
            UUID lina = fx.advisor(fx.user(UserRole.ADVISOR), 3, skill);
            UUID assigned = fx.conversation(customer, skill, ConversationStatus.ASSIGNED, "0", t0, karim, null);
            UUID active = fx.conversation(customer, skill, ConversationStatus.ACTIVE, "0", t0.plusSeconds(1), karim, null);
            UUID escalated = fx.conversation(customer, skill, ConversationStatus.ESCALATED, "0", t0.plusSeconds(2), karim, null);
            fx.conversation(customer, skill, ConversationStatus.RESOLVED, "0", t0, karim, null);
            fx.conversation(customer, skill, ConversationStatus.ACTIVE, "0", t0, lina, null);

            assertThat(lifecycle.heldBy(karim)).extracting(ConversationRecord::id)
                    .containsExactly(assigned, active, escalated);
        }
    }

    @Nested
    @DisplayName("messages")
    class Messages {

        @Test
        @DisplayName("an advisor's first message engages the conversation: ASSIGNED -> ACTIVE, in one transaction")
        void advisorEngages() {
            String skill = fx.skill(60);
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 1, skill);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.ASSIGNED, "0", t0, advisor, null);

            PostedMessage first = messages.post(c, MessageSender.ADVISOR, "Bonjour Madame Haddad", t0.plusSeconds(15));
            PostedMessage second = messages.post(c, MessageSender.ADVISOR, "Je bloque la carte.", t0.plusSeconds(20));

            assertThat(first.statusChanged()).isTrue();
            assertThat(first.conversation().status()).isEqualTo(ConversationStatus.ACTIVE);
            assertThat(second.statusChanged()).isFalse();
            assertThat(fx.reload(c).getStatus()).isEqualTo(ConversationStatus.ACTIVE);
            assertThat(messages.recent(c, 10)).extracting(MessageRecord::content)
                    .containsExactly("Bonjour Madame Haddad", "Je bloque la carte.");
        }

        @Test
        @DisplayName("an advisor cannot write in a queued conversation, a customer cannot write in a closed one")
        void refused() {
            String skill = fx.skill(60);
            UUID queued = fx.conversation(fx.customer(), skill, ConversationStatus.QUEUED, "0", t0);
            UUID resolved = fx.conversation(fx.customer(), skill, ConversationStatus.RESOLVED, "0", t0);

            assertThatThrownBy(() -> messages.post(queued, MessageSender.ADVISOR, "x", t0))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> messages.post(resolved, MessageSender.CUSTOMER, "x", t0))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThat(messages.recent(queued, 10)).isEmpty();
            assertThat(messages.recent(resolved, 10)).isEmpty();
        }

        @Test
        @DisplayName("a customer may write while queued; recent returns the latest N, oldest first")
        void recent() {
            String skill = fx.skill(60);
            UUID c = fx.conversation(fx.customer(), skill, ConversationStatus.QUEUED, "0", t0);
            for (int i = 1; i <= 5; i++) {
                messages.post(c, MessageSender.CUSTOMER, "m" + i, t0.plusSeconds(i));
            }
            assertThat(messages.recent(c, 3)).extracting(MessageRecord::content).containsExactly("m3", "m4", "m5");
            assertThat(fx.reload(c).getStatus()).isEqualTo(ConversationStatus.QUEUED);
        }
    }

    @Nested
    @DisplayName("live operations")
    class Live {

        @Test
        @DisplayName("queue depth: how many wait in a skill and how long the oldest has waited")
        void depth() {
            String skill = fx.skill(60);
            UUID customer = fx.customer();
            fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", t0);
            fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", t0.plusSeconds(30));
            fx.conversation(customer, skill, ConversationStatus.ACTIVE, "0", t0);

            QueueDepth depth = operations.queueDepths(t0.plusSeconds(90)).stream()
                    .filter(d -> d.skill().equals(skill)).findFirst().orElseThrow();

            assertThat(depth.waiting()).isEqualTo(2);
            assertThat(depth.oldestWaitSeconds()).isEqualTo(90);
        }

        @Test
        @DisplayName("an empty skill is listed with 0 waiting and no oldest wait")
        void emptySkill() {
            String skill = fx.skill(60);
            QueueDepth depth = operations.queueDepths(t0).stream()
                    .filter(d -> d.skill().equals(skill)).findFirst().orElseThrow();
            assertThat(depth.waiting()).isZero();
            assertThat(depth.oldestWaitSeconds()).isNull();
        }

        @Test
        @DisplayName("the KPI snapshot counts what happened since the start of the day, from the stored metrics")
        void snapshot() {
            // A day no other test writes into, so the window holds exactly this test's rows.
            Instant since = Instant.parse("2098-06-01T00:00:00Z");
            Instant now = since.plusSeconds(3600);
            LiveKpiSnapshot before = operations.snapshot(since, now);
            assertThat(before.resolvedToday() + before.abandonedToday()).isZero();

            String skill = fx.skill(60);
            UUID advisor = fx.advisor(fx.user(UserRole.ADVISOR), 5, skill);
            UUID customer = fx.customer();
            UUID met = fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", since.plusSeconds(10));
            UUID missed = fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", since.plusSeconds(10));
            UUID gone = fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", since.plusSeconds(10));
            lifecycle.assignNext(advisor, skill, since.plusSeconds(40));   // waited 30 s: met
            lifecycle.assignNext(advisor, skill, since.plusSeconds(100));  // waited 90 s: missed
            lifecycle.close(gone, ConversationStatus.QUEUED, ConversationStatus.ABANDONED, UUID.randomUUID(), since.plusSeconds(200));
            UUID first = met;
            lifecycle.close(first, ConversationStatus.ASSIGNED, ConversationStatus.ABANDONED, UUID.randomUUID(), since.plusSeconds(300));
            messages.post(missed, MessageSender.ADVISOR, "hello", since.plusSeconds(301));
            lifecycle.close(missed, ConversationStatus.ACTIVE, ConversationStatus.RESOLVED, UUID.randomUUID(), since.plusSeconds(400));

            LiveKpiSnapshot after = operations.snapshot(since, now);

            assertThat(after.resolvedToday()).isEqualTo(1);
            assertThat(after.abandonedToday()).isEqualTo(2);
            assertThat(after.schemaVersion()).isEqualTo(1);
            assertThat(after.since()).isEqualTo(since);
            assertThat(after.averageWaitSeconds()).isEqualTo(60.0);   // (30 + 90) / 2, the two assigned
            // 1 met (30 s), 1 missed (90 s), 1 gave up in the queue after 190 s: also a miss.
            assertThat(after.slaRatio()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(after.abandonRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("with nothing assigned or ended in the window, the ratios are null, never 0")
        void emptyWindow() {
            Instant farFuture = Instant.parse("2099-01-01T00:00:00Z");
            LiveKpiSnapshot s = operations.snapshot(farFuture, farFuture.plusSeconds(60));
            assertThat(s.averageWaitSeconds()).isNull();
            assertThat(s.slaRatio()).isNull();
            assertThat(s.abandonRate()).isNull();
            assertThat(s.resolvedToday()).isZero();
        }
    }

    @Nested
    @DisplayName("advisor directory")
    class Directory {

        @Test
        @DisplayName("the advisor behind a login, with the skills they hold")
        void found() {
            String a = fx.skill(60);
            String b = fx.skill(60);
            UUID user = fx.user(UserRole.ADVISOR);
            UUID advisor = fx.advisor(user, 2, a, b);

            AdvisorDirectory.AdvisorProfile p = advisors.findByUserId(user).orElseThrow();

            assertThat(p.id()).isEqualTo(advisor);
            assertThat(p.maxConcurrent()).isEqualTo(2);
            assertThat(p.skills()).containsExactlyInAnyOrder(a, b);
        }

        @Test
        @DisplayName("no advisor row, or two for one login, is no profile: never a guess")
        void ambiguous() {
            assertThat(advisors.findByUserId(fx.user(UserRole.ADVISOR))).isEmpty();
            String skill = fx.skill(60);
            UUID shared = fx.user(UserRole.ADVISOR);
            fx.advisor(shared, 1, skill);
            fx.advisor(shared, 1, skill);
            assertThat(advisors.findByUserId(shared)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ concurrency helpers

    @SafeVarargs
    private static <T> List<T> race(Callable<T>... calls) throws Exception {
        List<Object> outcomes = raceOutcomes(calls);
        List<T> results = new ArrayList<>();
        for (Object o : outcomes) {
            if (o instanceof Throwable t) {
                throw new AssertionError("a racer failed", t);
            }
            @SuppressWarnings("unchecked") T value = (T) o;
            results.add(value);
        }
        return results;
    }

    /** Starts every call at the same instant; returns each result or the exception it threw. */
    @SafeVarargs
    private static List<Object> raceOutcomes(Callable<?>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
