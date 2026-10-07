package com.callverse.core.application.features.conversation;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.AdvisorDirectory;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import com.callverse.core.application.interfaces.LiveOperations;
import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import com.callverse.core.domain.services.ConversationRules;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory conversation store for the use-case tests: the same rules as the adapter
 * ({@link ConversationRules}), none of the database. Concurrency and SQL are the adapter test's job.
 */
public final class InMemoryConversations implements ConversationLifecycle, ConversationMessages, LiveOperations,
        AdvisorDirectory {

    public final Map<UUID, ConversationRecord> conversations = new LinkedHashMap<>();
    public final List<MessageRecord> messages = new ArrayList<>();
    public final Map<UUID, AdvisorProfile> advisorsByUser = new HashMap<>();
    public final List<String> skills = new ArrayList<>(List.of("ACCOUNTS", "CARDS", "CREDIT", "FRAUD"));
    private long nextMessageId = 1;

    // ------------------------------------------------------------------ fixtures

    public ConversationRecord put(ConversationStatus status, String skill, UUID customerUser, UUID advisorUser) {
        UUID advisorId = advisorUser == null ? null : advisorFor(advisorUser).id();
        ConversationRecord r = new ConversationRecord(UUID.randomUUID(), UUID.randomUUID(), customerUser, advisorId,
                advisorUser, skill, Intent.CARD, Channel.CHAT, status, Instant.parse("2026-10-02T07:00:00Z"),
                advisorUser == null ? null : Instant.parse("2026-10-02T07:00:30Z"), null);
        conversations.put(r.id(), r);
        return r;
    }

    public AdvisorProfile advisor(UUID userId, String... skills) {
        AdvisorProfile p = new AdvisorProfile(UUID.randomUUID(), userId, "Karim", 1, java.util.Set.of(skills));
        advisorsByUser.put(userId, p);
        return p;
    }

    private AdvisorProfile advisorFor(UUID userId) {
        return advisorsByUser.computeIfAbsent(userId,
                u -> new AdvisorProfile(UUID.randomUUID(), u, "Advisor", 1, java.util.Set.of("CARDS")));
    }

    // ------------------------------------------------------------------ ConversationLifecycle

    @Override
    public ConversationRecord open(UUID customerId, String skillCode, Intent intent, Channel channel, Instant now) {
        if (!skills.contains(skillCode)) {
            throw new ResourceNotFoundException("Skill", skillCode);
        }
        ConversationRecord r = new ConversationRecord(UUID.randomUUID(), customerId, null, null, null, skillCode,
                intent, channel, ConversationStatus.QUEUED, now, null, null);
        conversations.put(r.id(), r);
        return r;
    }

    @Override
    public Optional<ConversationRecord> find(UUID conversationId) {
        return Optional.ofNullable(conversations.get(conversationId));
    }

    @Override
    public Optional<ConversationRecord> assignNext(UUID advisorId, String skillCode, Instant now) {
        AdvisorProfile advisor = advisorsByUser.values().stream().filter(a -> a.id().equals(advisorId))
                .findFirst().orElseThrow();
        long held = conversations.values().stream().filter(c -> advisorId.equals(c.advisorId())
                && (c.status() == ConversationStatus.ASSIGNED || c.status() == ConversationStatus.ACTIVE)).count();
        ConversationRules.requireCapacity(advisorId, held, advisor.maxConcurrent());
        Optional<ConversationRecord> head = conversations.values().stream()
                .filter(c -> c.status() == ConversationStatus.QUEUED && c.skill().equals(skillCode))
                .min(Comparator.comparing(ConversationRecord::queuedAt));
        return head.map(c -> {
            ConversationRules.assign(c.status(), c.queuedAt(), now, 60);
            ConversationRecord r = with(c, ConversationStatus.ASSIGNED, advisorId, advisor.userId(), now, null);
            conversations.put(r.id(), r);
            return r;
        });
    }

    @Override
    public ConversationRecord close(UUID conversationId, ConversationStatus expectedFrom, ConversationStatus target,
            UUID closedByUserId, Instant now) {
        ConversationRecord c = find(conversationId).orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
        if (c.status() != expectedFrom) {
            throw new InvalidStateTransitionException(c.status(), target);
        }
        ConversationRules.close(c.status(), target, c.queuedAt(), c.assignedAt(), now);
        ConversationRecord r = with(c, target, c.advisorId(), c.advisorUserId(), c.assignedAt(), now);
        conversations.put(r.id(), r);
        return r;
    }

    @Override
    public List<ConversationRecord> heldBy(UUID advisorId) {
        return conversations.values().stream().filter(c -> advisorId.equals(c.advisorId())
                && !c.status().isTerminal() && c.status() != ConversationStatus.QUEUED).toList();
    }

    // ------------------------------------------------------------------ ConversationMessages

    @Override
    public PostedMessage post(UUID conversationId, MessageSender sender, String content, Instant now) {
        ConversationRecord c = find(conversationId).orElseThrow();
        ConversationStatus after = sender == MessageSender.ADVISOR
                ? ConversationRules.statusAfterAdvisorMessage(c.status())
                : c.status();
        if (sender == MessageSender.CUSTOMER && !ConversationRules.acceptsCustomerMessage(c.status())) {
            throw InvalidStateTransitionException.noMessagesIn(c.status(), "customer");
        }
        ConversationRecord updated = with(c, after, c.advisorId(), c.advisorUserId(), c.assignedAt(), c.endedAt());
        conversations.put(c.id(), updated);
        MessageRecord m = new MessageRecord(nextMessageId++, conversationId, sender, content, now);
        messages.add(m);
        return new PostedMessage(m, updated, after != c.status());
    }

    @Override
    public List<MessageRecord> recent(UUID conversationId, int limit) {
        List<MessageRecord> all = messages.stream().filter(m -> m.conversationId().equals(conversationId)).toList();
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }

    // ------------------------------------------------------------------ LiveOperations

    @Override
    public List<QueueDepth> queueDepths(Instant now) {
        return skills.stream().map(s -> new QueueDepth(s,
                conversations.values().stream().filter(c -> c.skill().equals(s)
                        && c.status() == ConversationStatus.QUEUED).count(), null)).toList();
    }

    @Override
    public LiveKpiSnapshot snapshot(Instant since, Instant now) {
        List<QueueDepth> queues = queueDepths(now);
        return new LiveKpiSnapshot(LiveKpiSnapshot.SCHEMA_VERSION, now, since, queues,
                queues.stream().mapToLong(QueueDepth::waiting).sum(), 0, 0, 0, null, null, null);
    }

    // ------------------------------------------------------------------ AdvisorDirectory

    @Override
    public Optional<AdvisorProfile> findByUserId(UUID userId) {
        return Optional.ofNullable(advisorsByUser.get(userId));
    }

    private static ConversationRecord with(ConversationRecord c, ConversationStatus status, UUID advisorId,
            UUID advisorUser, Instant assignedAt, Instant endedAt) {
        return new ConversationRecord(c.id(), c.customerId(), c.customerUserId(), advisorId, advisorUser, c.skill(),
                c.intent(), c.channel(), status, c.queuedAt(), assignedAt, endedAt);
    }
}
