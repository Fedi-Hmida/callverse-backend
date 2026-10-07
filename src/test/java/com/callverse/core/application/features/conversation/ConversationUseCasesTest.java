package com.callverse.core.application.features.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.RecordingPublisher;
import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.AdvisorProfileNotFoundException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.commands.CloseConversationCommand;
import com.callverse.core.application.features.conversation.commands.CloseConversationCommandHandler;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommand;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommandHandler;
import com.callverse.core.application.features.conversation.commands.PostMessageCommand;
import com.callverse.core.application.features.conversation.commands.PostMessageCommandHandler;
import com.callverse.core.application.features.conversation.commands.TakeNextConversationCommand;
import com.callverse.core.application.features.conversation.commands.TakeNextConversationCommandHandler;
import com.callverse.core.application.features.conversation.queries.ConversationSubscriptionPolicy;
import com.callverse.core.application.features.conversation.queries.GetConversationQuery;
import com.callverse.core.application.features.conversation.queries.GetConversationQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetHeldConversationsQuery;
import com.callverse.core.application.features.conversation.queries.GetHeldConversationsQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetLiveKpiQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetQueuesQuery;
import com.callverse.core.application.features.conversation.queries.GetQueuesQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetTranscriptQuery;
import com.callverse.core.application.features.conversation.queries.GetTranscriptQueryHandler;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import com.callverse.core.application.interfaces.LiveOperations.QueueDepth;
import com.callverse.core.application.interfaces.QueueEvent;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The conversation use cases over in-memory ports: who may do what, what each step announces, and
 * that a failed announcement never fails the step. Plain unit tests, no Spring.
 */
class ConversationUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-02T07:01:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final InMemoryConversations store = new InMemoryConversations();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final ConversationEvents events = new ConversationEvents(publisher, store, CLOCK, PARIS);

    private final UUID karimUser = UUID.randomUUID();
    private final UUID linaUser = UUID.randomUUID();
    private final UUID aminaUser = UUID.randomUUID();

    private static AuthenticatedPrincipal as(UUID user, UserRole role) {
        return new AuthenticatedPrincipal(user, role.name().toLowerCase() + "@test.local", role);
    }

    private AuthenticatedPrincipal karim() {
        return as(karimUser, UserRole.ADVISOR);
    }

    private AuthenticatedPrincipal lina() {
        return as(linaUser, UserRole.ADVISOR);
    }

    private AuthenticatedPrincipal amina() {
        return as(aminaUser, UserRole.CUSTOMER);
    }

    private static AuthenticatedPrincipal sarah() {
        return as(UUID.randomUUID(), UserRole.SUPERVISOR);
    }

    private static AuthenticatedPrincipal admin() {
        return as(UUID.randomUUID(), UserRole.ADMIN);
    }

    // ------------------------------------------------------------------ open

    @Nested
    @DisplayName("open a contact")
    class Open {

        @Test
        @DisplayName("queues it at now and announces the arrival, the queue's new depth and the KPIs")
        void opens() {
            OpenConversationCommandHandler handler = new OpenConversationCommandHandler(store, events, CLOCK);

            ConversationRecord r = handler.handle(new OpenConversationCommand(UUID.randomUUID(), "FRAUD", Intent.FRAUD));

            assertThat(r.status()).isEqualTo(ConversationStatus.QUEUED);
            assertThat(r.queuedAt()).isEqualTo(NOW);
            assertThat(publisher.queueEvents).singleElement().satisfies(e -> {
                assertThat(e.type()).isEqualTo(QueueEvent.Type.CONVERSATION_QUEUED);
                assertThat(e.skill()).isEqualTo("FRAUD");
                assertThat(e.conversationId()).isEqualTo(r.id());
                assertThat(e.waiting()).isEqualTo(1);
            });
            assertThat(publisher.kpis).hasSize(1);
        }

        @Test
        @DisplayName("a broker that fails does not fail the contact: it is queued all the same")
        void brokerFailure() {
            RealtimeEventPublisher broken = new RecordingPublisher() {
                @Override
                public void publishQueueEvent(QueueEvent event) {
                    throw new IllegalStateException("broker down");
                }

                @Override
                public void publishLiveKpi(LiveKpiSnapshot snapshot) {
                    throw new IllegalStateException("broker down");
                }
            };
            OpenConversationCommandHandler handler = new OpenConversationCommandHandler(
                    store, new ConversationEvents(broken, store, CLOCK, PARIS), CLOCK);

            ConversationRecord r = handler.handle(new OpenConversationCommand(UUID.randomUUID(), "CARDS", null));

            assertThat(store.find(r.id())).isPresent();
        }
    }

    // ------------------------------------------------------------------ take next

    @Nested
    @DisplayName("take the next conversation")
    class TakeNext {

        @Test
        @DisplayName("an advisor holding the skill takes it; the queue, the conversation and the KPIs are told")
        void takes() {
            store.advisor(karimUser, "CARDS");
            ConversationRecord waiting = store.put(ConversationStatus.QUEUED, "CARDS", aminaUser, null);
            TakeNextConversationCommandHandler handler =
                    new TakeNextConversationCommandHandler(store, store, events, CLOCK);

            ConversationRecord taken = handler.handle(new TakeNextConversationCommand(karim(), "CARDS")).orElseThrow();

            assertThat(taken.id()).isEqualTo(waiting.id());
            assertThat(taken.status()).isEqualTo(ConversationStatus.ASSIGNED);
            assertThat(taken.advisorUserId()).isEqualTo(karimUser);
            assertThat(publisher.queueEvents).singleElement().satisfies(e -> {
                assertThat(e.type()).isEqualTo(QueueEvent.Type.CONVERSATION_LEFT_QUEUE);
                assertThat(e.status()).isEqualTo(ConversationStatus.ASSIGNED);
                assertThat(e.waiting()).isZero();
            });
            assertThat(publisher.conversationEvents).singleElement()
                    .extracting(ConversationEvent::type).isEqualTo(ConversationEvent.Type.STATUS_CHANGED);
            assertThat(publisher.kpis).hasSize(1);
        }

        @Test
        @DisplayName("an empty queue answers nothing and announces nothing")
        void empty() {
            store.advisor(karimUser, "CARDS");
            TakeNextConversationCommandHandler handler =
                    new TakeNextConversationCommandHandler(store, store, events, CLOCK);

            assertThat(handler.handle(new TakeNextConversationCommand(karim(), "CARDS"))).isEmpty();
            assertThat(publisher.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a queue the advisor holds no skill for is refused (B6), and nothing is taken")
        void notTheirSkill() {
            store.advisor(karimUser, "CARDS");
            ConversationRecord waiting = store.put(ConversationStatus.QUEUED, "FRAUD", null, null);
            TakeNextConversationCommandHandler handler =
                    new TakeNextConversationCommandHandler(store, store, events, CLOCK);

            assertThatThrownBy(() -> handler.handle(new TakeNextConversationCommand(karim(), "FRAUD")))
                    .isInstanceOf(ActionNotPermittedException.class);
            assertThat(store.find(waiting.id()).orElseThrow().status()).isEqualTo(ConversationStatus.QUEUED);
        }

        @Test
        @DisplayName("an advisor login with no advisor profile is ADVISOR_PROFILE_NOT_FOUND")
        void noProfile() {
            TakeNextConversationCommandHandler handler =
                    new TakeNextConversationCommandHandler(store, store, events, CLOCK);

            assertThatThrownBy(() -> handler.handle(new TakeNextConversationCommand(karim(), "CARDS")))
                    .isInstanceOf(AdvisorProfileNotFoundException.class)
                    .extracting("code").isEqualTo("ADVISOR_PROFILE_NOT_FOUND");
        }
    }

    // ------------------------------------------------------------------ messages

    @Nested
    @DisplayName("post a message")
    class Post {

        @Test
        @DisplayName("the assigned advisor writes as ADVISOR; the first message engages the conversation")
        void advisor() {
            ConversationRecord c = store.put(ConversationStatus.ASSIGNED, "CARDS", aminaUser, karimUser);
            PostMessageCommandHandler handler = new PostMessageCommandHandler(store, store, events, CLOCK);

            MessageRecord m = handler.handle(new PostMessageCommand(karim(), c.id(), "  Bonjour Madame Haddad  "));

            assertThat(m.sender()).isEqualTo(MessageSender.ADVISOR);
            assertThat(m.content()).isEqualTo("Bonjour Madame Haddad");
            assertThat(store.find(c.id()).orElseThrow().status()).isEqualTo(ConversationStatus.ACTIVE);
            assertThat(publisher.conversationEvents).extracting(ConversationEvent::type)
                    .containsExactly(ConversationEvent.Type.MESSAGE_POSTED, ConversationEvent.Type.STATUS_CHANGED);
            assertThat(publisher.conversationEvents.get(0).content()).isEqualTo("Bonjour Madame Haddad");
        }

        @Test
        @DisplayName("the owning customer writes as CUSTOMER, even while waiting in the queue")
        void customer() {
            ConversationRecord c = store.put(ConversationStatus.QUEUED, "CARDS", aminaUser, null);
            PostMessageCommandHandler handler = new PostMessageCommandHandler(store, store, events, CLOCK);

            MessageRecord m = handler.handle(new PostMessageCommand(amina(), c.id(), "Ma carte est bloquee"));

            assertThat(m.sender()).isEqualTo(MessageSender.CUSTOMER);
            assertThat(publisher.conversationEvents).extracting(ConversationEvent::type)
                    .containsExactly(ConversationEvent.Type.MESSAGE_POSTED);
            assertThat(publisher.kpis).as("a message is not a lifecycle change").isEmpty();
        }

        @Test
        @DisplayName("another advisor, another customer: 404, as if it did not exist; nothing is written")
        void strangers() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);
            PostMessageCommandHandler handler = new PostMessageCommandHandler(store, store, events, CLOCK);

            assertThatThrownBy(() -> handler.handle(new PostMessageCommand(lina(), c.id(), "x")))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> handler.handle(new PostMessageCommand(
                    as(UUID.randomUUID(), UserRole.CUSTOMER), c.id(), "x")))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(store.messages).isEmpty();
            assertThat(publisher.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a supervisor reads the chat but does not write in it: 403")
        void supervisorReadsOnly() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);
            PostMessageCommandHandler handler = new PostMessageCommandHandler(store, store, events, CLOCK);

            assertThatThrownBy(() -> handler.handle(new PostMessageCommand(sarah(), c.id(), "x")))
                    .isInstanceOf(ActionNotPermittedException.class)
                    .extracting("code").isEqualTo("ACCESS_DENIED");
        }

        @Test
        @DisplayName("a blank message is VALIDATION_FAILED before anything is looked up")
        void blank() {
            PostMessageCommandHandler handler = new PostMessageCommandHandler(store, store, events, CLOCK);
            assertThatThrownBy(() -> handler.handle(new PostMessageCommand(karim(), UUID.randomUUID(), "   ")))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    // ------------------------------------------------------------------ close

    @Nested
    @DisplayName("resolve and abandon")
    class Close {

        private CloseConversationCommandHandler handler() {
            return new CloseConversationCommandHandler(store, events, CLOCK);
        }

        @Test
        @DisplayName("the assigned advisor resolves an ACTIVE conversation; the topic and the KPIs are told")
        void advisorResolves() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);

            ConversationRecord r = handler().handle(new CloseConversationCommand(karim(), c.id(), ConversationStatus.RESOLVED));

            assertThat(r.status()).isEqualTo(ConversationStatus.RESOLVED);
            assertThat(r.endedAt()).isEqualTo(NOW);
            assertThat(publisher.conversationEvents).singleElement()
                    .satisfies(e -> assertThat(e.status()).isEqualTo(ConversationStatus.RESOLVED));
            assertThat(publisher.queueEvents).isEmpty();
            assertThat(publisher.kpis).hasSize(1);
        }

        @Test
        @DisplayName("an ESCALATED conversation is the supervisor's: the advisor gets 403, the supervisor resolves it")
        void escalatedIsTheSupervisors() {
            ConversationRecord c = store.put(ConversationStatus.ESCALATED, "CARDS", aminaUser, karimUser);

            assertThatThrownBy(() -> handler().handle(new CloseConversationCommand(karim(), c.id(), ConversationStatus.RESOLVED)))
                    .isInstanceOf(ActionNotPermittedException.class);
            assertThatThrownBy(() -> handler().handle(new CloseConversationCommand(admin(), c.id(), ConversationStatus.RESOLVED)))
                    .as("ADMIN administers; it does not serve customers")
                    .isInstanceOf(ActionNotPermittedException.class);

            assertThat(handler().handle(new CloseConversationCommand(sarah(), c.id(), ConversationStatus.RESOLVED)).status())
                    .isEqualTo(ConversationStatus.RESOLVED);
        }

        @Test
        @DisplayName("the customer who hangs up while waiting abandons it: it leaves the queue")
        void customerAbandonsInQueue() {
            ConversationRecord c = store.put(ConversationStatus.QUEUED, "CARDS", aminaUser, null);

            handler().handle(new CloseConversationCommand(amina(), c.id(), ConversationStatus.ABANDONED));

            assertThat(publisher.queueEvents).singleElement().satisfies(e -> {
                assertThat(e.type()).isEqualTo(QueueEvent.Type.CONVERSATION_LEFT_QUEUE);
                assertThat(e.status()).isEqualTo(ConversationStatus.ABANDONED);
            });
        }

        @Test
        @DisplayName("a stranger is answered 404; nothing changes")
        void stranger() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);

            assertThatThrownBy(() -> handler().handle(new CloseConversationCommand(lina(), c.id(), ConversationStatus.ABANDONED)))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(store.find(c.id()).orElseThrow().status()).isEqualTo(ConversationStatus.ACTIVE);
        }

        @Test
        @DisplayName("an event carries when the change happened, not when it was published")
        void eventTime() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);
            Clock later = Clock.offset(CLOCK, java.time.Duration.ofSeconds(5));
            CloseConversationCommandHandler handler =
                    new CloseConversationCommandHandler(store, new ConversationEvents(publisher, store, later, PARIS), CLOCK);

            handler.handle(new CloseConversationCommand(karim(), c.id(), ConversationStatus.RESOLVED));

            assertThat(publisher.conversationEvents).singleElement()
                    .satisfies(e -> assertThat(e.occurredAt()).isEqualTo(NOW));
        }

        @Test
        @DisplayName("an escalated conversation is never abandoned, even by a supervisor: 409")
        void escalatedNotAbandoned() {
            ConversationRecord c = store.put(ConversationStatus.ESCALATED, "CARDS", aminaUser, karimUser);
            assertThatThrownBy(() -> handler().handle(new CloseConversationCommand(sarah(), c.id(), ConversationStatus.ABANDONED)))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("an illegal close is INVALID_STATE_TRANSITION and announces nothing")
        void illegal() {
            ConversationRecord c = store.put(ConversationStatus.RESOLVED, "CARDS", aminaUser, karimUser);

            assertThatThrownBy(() -> handler().handle(new CloseConversationCommand(karim(), c.id(), ConversationStatus.ABANDONED)))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThat(publisher.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("close only resolves or abandons: any other target is VALIDATION_FAILED")
        void onlyEndStates() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);
            assertThatThrownBy(() -> handler().handle(new CloseConversationCommand(karim(), c.id(), ConversationStatus.ESCALATED)))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    // ------------------------------------------------------------------ queries

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("a conversation is read by its customer, its advisor and supervisors; anyone else gets 404")
        void read() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);
            GetConversationQueryHandler handler = new GetConversationQueryHandler(store);

            assertThat(handler.handle(new GetConversationQuery(amina(), c.id())).id()).isEqualTo(c.id());
            assertThat(handler.handle(new GetConversationQuery(karim(), c.id())).id()).isEqualTo(c.id());
            assertThat(handler.handle(new GetConversationQuery(sarah(), c.id())).id()).isEqualTo(c.id());
            assertThatThrownBy(() -> handler.handle(new GetConversationQuery(lina(), c.id())))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> handler.handle(new GetConversationQuery(sarah(), UUID.randomUUID())))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a QUEUED conversation is owned by no advisor: even one holding the skill gets 404")
        void queuedOwnedByNobody() {
            store.advisor(karimUser, "CARDS");
            ConversationRecord c = store.put(ConversationStatus.QUEUED, "CARDS", aminaUser, null);
            assertThatThrownBy(() -> new GetConversationQueryHandler(store).handle(new GetConversationQuery(karim(), c.id())))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("the transcript is bounded: 1 to 200 messages, otherwise VALIDATION_FAILED")
        void transcriptBounds() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);
            GetTranscriptQueryHandler handler = new GetTranscriptQueryHandler(store, store);

            assertThat(handler.handle(new GetTranscriptQuery(karim(), c.id(), 200))).isEmpty();
            assertThatThrownBy(() -> handler.handle(new GetTranscriptQuery(karim(), c.id(), 0)))
                    .isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> handler.handle(new GetTranscriptQuery(karim(), c.id(), 201)))
                    .isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> handler.handle(new GetTranscriptQuery(lina(), c.id(), 50)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("an advisor sees only the queues of skills they hold (B6); a supervisor sees every queue")
        void queues() {
            store.advisor(karimUser, "CARDS", "FRAUD");
            GetQueuesQueryHandler handler = new GetQueuesQueryHandler(store, store, CLOCK);

            assertThat(handler.handle(new GetQueuesQuery(karim()))).extracting(QueueDepth::skill)
                    .containsExactlyInAnyOrder("CARDS", "FRAUD");
            assertThat(handler.handle(new GetQueuesQuery(sarah()))).extracting(QueueDepth::skill)
                    .containsExactlyInAnyOrder("ACCOUNTS", "CARDS", "CREDIT", "FRAUD");
            assertThatThrownBy(() -> handler.handle(new GetQueuesQuery(lina())))
                    .isInstanceOf(AdvisorProfileNotFoundException.class);
        }

        @Test
        @DisplayName("an advisor's own conversations, never another advisor's")
        void held() {
            store.advisor(karimUser, "CARDS");
            store.advisor(linaUser, "CARDS");
            ConversationRecord mine = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);
            store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, linaUser);

            assertThat(new GetHeldConversationsQueryHandler(store, store).handle(new GetHeldConversationsQuery(karim())))
                    .extracting(ConversationRecord::id).containsExactly(mine.id());
        }

        @Test
        @DisplayName("the live KPIs count from local midnight in Paris (22:00 UTC the day before, in summer)")
        void kpiDay() {
            LiveKpiSnapshot s = new GetLiveKpiQueryHandler(store, events, CLOCK).handle();
            assertThat(s.since()).isEqualTo(Instant.parse("2026-10-01T22:00:00Z"));
            assertThat(s.at()).isEqualTo(NOW);
        }
    }

    // ------------------------------------------------------------------ subscriptions

    @Nested
    @DisplayName("who may listen")
    class Subscriptions {

        @Test
        @DisplayName("a conversation topic: the same people who may read it, no one else")
        void conversationTopic() {
            ConversationRecord c = store.put(ConversationStatus.ACTIVE, "CARDS", aminaUser, karimUser);
            ConversationSubscriptionPolicy policy = new ConversationSubscriptionPolicy(store, store);

            assertThat(policy.mayListenToConversation(karim(), c.id().toString())).isTrue();
            assertThat(policy.mayListenToConversation(amina(), c.id().toString())).isTrue();
            assertThat(policy.mayListenToConversation(sarah(), c.id().toString())).isTrue();
            assertThat(policy.mayListenToConversation(lina(), c.id().toString())).isFalse();
            assertThat(policy.mayListenToConversation(sarah(), UUID.randomUUID().toString())).isFalse();
            assertThat(policy.mayListenToConversation(sarah(), "not-a-uuid")).isFalse();
            assertThat(policy.mayListenToConversation(sarah(), "1-1-1-1-1"))
                    .as("a lenient UUID form names no topic anything is ever published to").isFalse();
            assertThat(policy.mayListenToConversation(sarah(), c.id().toString().toUpperCase()))
                    .as("events go to the lower-case id only").isFalse();
        }

        @Test
        @DisplayName("a queue topic: an advisor holding the skill, or a supervisor; never a customer")
        void queueTopic() {
            store.advisor(karimUser, "CARDS");
            ConversationSubscriptionPolicy policy = new ConversationSubscriptionPolicy(store, store);

            assertThat(policy.mayListenToQueue(karim(), "CARDS")).isTrue();
            assertThat(policy.mayListenToQueue(karim(), "FRAUD")).isFalse();
            assertThat(policy.mayListenToQueue(lina(), "CARDS")).as("no advisor profile").isFalse();
            assertThat(policy.mayListenToQueue(sarah(), "FRAUD")).isTrue();
            assertThat(policy.mayListenToQueue(admin(), "FRAUD")).isTrue();
            assertThat(policy.mayListenToQueue(amina(), "CARDS")).isFalse();
        }
    }
}
