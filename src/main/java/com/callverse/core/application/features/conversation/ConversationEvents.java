package com.callverse.core.application.features.conversation;

import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages.PostedMessage;
import com.callverse.core.application.interfaces.LiveOperations;
import com.callverse.core.application.interfaces.QueueEvent;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.domain.enums.ConversationStatus;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * Announces what a conversation use case changed, on the three live topics: the skill queue, the
 * conversation itself, and the supervision KPI banner.
 *
 * <p><strong>Called after the change is committed</strong> (the adapters commit before returning),
 * and the figures are read then, so the waiting count and the KPIs include the change.
 *
 * <p><strong>Never fails the use case.</strong> The change is already stored; a broken broker or a
 * failed KPI read must not turn the advisor's successful action into an error. A failure is logged
 * and the next change publishes fresh figures anyway.
 */
public class ConversationEvents {

    private static final System.Logger LOG = System.getLogger(ConversationEvents.class.getName());

    private final RealtimeEventPublisher publisher;
    private final LiveOperations operations;
    private final Clock clock;
    private final ZoneId zone;

    public ConversationEvents(RealtimeEventPublisher publisher, LiveOperations operations, Clock clock, ZoneId zone) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /** "Today" for the KPIs: local midnight in the operations time zone. */
    public Instant startOfDay(Instant now) {
        return LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant();
    }

    /** A contact entered its queue. */
    public void queued(ConversationRecord conversation) {
        safely(() -> queue(conversation, QueueEvent.Type.CONVERSATION_QUEUED));
        safely(this::kpi);
    }

    /** A conversation left its queue: taken by an advisor, or abandoned while waiting. */
    public void leftQueue(ConversationRecord conversation) {
        safely(() -> queue(conversation, QueueEvent.Type.CONVERSATION_LEFT_QUEUE));
        safely(() -> publisher.publishConversationEvent(ConversationEvent.statusChanged(
                conversation.id(), conversation.status(), changedAt(conversation))));
        safely(this::kpi);
    }

    /** Any other status change: engaged, escalated, resolved, abandoned in service. */
    public void statusChanged(ConversationRecord conversation) {
        safely(() -> publisher.publishConversationEvent(ConversationEvent.statusChanged(
                conversation.id(), conversation.status(), changedAt(conversation))));
        safely(this::kpi);
    }

    /** A status change known only by id: the escalation path, which holds no full record. */
    public void statusChanged(UUID conversationId, ConversationStatus status) {
        safely(() -> publisher.publishConversationEvent(
                ConversationEvent.statusChanged(conversationId, status, clock.instant())));
        safely(this::kpi);
    }

    /** A message, and the engagement it caused if it was the advisor's first (at the message's time). */
    public void messagePosted(PostedMessage posted) {
        safely(() -> publisher.publishConversationEvent(
                ConversationEvent.messagePosted(posted.message(), posted.conversation().status())));
        if (posted.statusChanged()) {
            safely(() -> publisher.publishConversationEvent(ConversationEvent.statusChanged(
                    posted.conversation().id(), posted.conversation().status(), posted.message().sentAt())));
            safely(this::kpi);
        }
    }

    /**
     * When the change happened, from the stored timestamps — not when it is published. Requests
     * publish from their own threads, so frames can arrive out of order; a client keeps the latest
     * {@code occurredAt} per conversation and ignores older status frames.
     */
    private static Instant changedAt(ConversationRecord c) {
        if (c.endedAt() != null) {
            return c.endedAt();
        }
        return c.assignedAt() != null ? c.assignedAt() : c.queuedAt();
    }

    private void queue(ConversationRecord conversation, QueueEvent.Type type) {
        Instant now = clock.instant();
        long waiting = operations.queueDepths(now).stream()
                .filter(depth -> depth.skill().equals(conversation.skill()))
                .mapToLong(LiveOperations.QueueDepth::waiting)
                .sum();
        publisher.publishQueueEvent(new QueueEvent(QueueEvent.SCHEMA_VERSION, type, changedAt(conversation), conversation.skill(),
                conversation.id(), conversation.status(), waiting));
    }

    private void kpi() {
        Instant now = clock.instant();
        publisher.publishLiveKpi(operations.snapshot(startOfDay(now), now));
    }

    private static void safely(Runnable announcement) {
        try {
            announcement.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Live event not published: {0}", e.toString());
        }
    }
}
