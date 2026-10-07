package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.exceptions.SlaPolicyNotFoundException;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Escalation;
import com.callverse.core.domain.entities.Message;
import com.callverse.core.domain.entities.Skill;
import com.callverse.core.domain.entities.SlaPolicy;
import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationStatus;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import com.callverse.core.domain.services.ConversationRules;
import com.callverse.core.domain.services.PriorityCalculator;
import com.callverse.infrastructure.persistence.repositories.AdvisorRepository;
import com.callverse.infrastructure.persistence.repositories.AppUserRepository;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import com.callverse.infrastructure.persistence.repositories.CustomerRepository;
import com.callverse.infrastructure.persistence.repositories.EscalationRepository;
import com.callverse.infrastructure.persistence.repositories.MessageRepository;
import com.callverse.infrastructure.persistence.repositories.SkillRepository;
import com.callverse.infrastructure.persistence.repositories.SlaPolicyRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link ConversationLifecycle} and {@link ConversationMessages} with Spring Data.
 *
 * <p><strong>Lock, then decide.</strong> Every write locks the rows it changes, then applies the
 * pure rule ({@link ConversationRules}) to the state it read under that lock, all in one
 * transaction. A caller that lost a race therefore sees the winner's result and is refused by the
 * rule, never silently overwrites it.
 *
 * <p><strong>Lock order is advisor, then conversation</strong>, and only {@link #assignNext} takes
 * both; every other write takes the conversation alone. One order everywhere means no deadlock. The
 * foreign-key share locks PostgreSQL adds (inserting a message, setting {@code advisor_id}) fall on
 * rows the transaction already holds, so they add no ordering of their own.
 *
 * <p><strong>Ownership is decided before the lock, the state under it.</strong> Who may act (the
 * assigned advisor, the owning customer) is read by the use case; the adapter re-checks only the
 * status. That is sound because {@code advisor_id} is written once, at QUEUED → ASSIGNED, and no
 * route changes {@code customer.user_id}: a stale read can only wrongly refuse. A future reassignment
 * or transfer must pass the expected owner here and refuse under the lock, as {@code expectedFrom}
 * does for the status.
 *
 * <p>Timestamps are truncated to microseconds, PostgreSQL's precision, so a value re-read from the
 * database equals the one written.
 */
@Component
@RequiredArgsConstructor
class ConversationLifecycleAdapter implements ConversationLifecycle, ConversationMessages {

    /** What counts against {@code max_concurrent}: an escalated conversation is the supervisor's. */
    private static final Set<ConversationStatus> HELD_FOR_CAPACITY =
            EnumSet.of(ConversationStatus.ASSIGNED, ConversationStatus.ACTIVE);

    private static final Set<ConversationStatus> HELD =
            EnumSet.of(ConversationStatus.ASSIGNED, ConversationStatus.ACTIVE, ConversationStatus.ESCALATED);

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final CustomerRepository customers;
    private final SkillRepository skills;
    private final AdvisorRepository advisors;
    private final SlaPolicyRepository slaPolicies;
    private final EscalationRepository escalations;
    private final AppUserRepository users;

    // ------------------------------------------------------------------ lifecycle

    @Override
    @Transactional
    public ConversationRecord open(UUID customerId, String skillCode, Intent intent, Channel channel, Instant now) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
        Skill skill = skill(skillCode);

        Conversation conversation = new Conversation();
        conversation.setCustomer(customer);
        conversation.setSkill(skill);
        conversation.setIntent(intent);
        conversation.setChannel(channel);
        conversation.setStatus(ConversationStatus.QUEUED);
        conversation.setPriorityScore(
                PriorityCalculator.score(customer.getChurnRisk(), customer.getSegment(), intent));
        conversation.setQueuedAt(micros(now));
        return toRecord(conversations.save(conversation));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationRecord> find(UUID conversationId) {
        return conversations.findLive(conversationId).map(ConversationLifecycleAdapter::toRecord);
    }

    @Override
    @Transactional
    public Optional<ConversationRecord> assignNext(UUID advisorId, String skillCode, Instant now) {
        Advisor advisor = advisors.findByIdForUpdate(advisorId)
                .orElseThrow(() -> new ResourceNotFoundException("Advisor", advisorId));
        ConversationRules.requireCapacity(
                advisorId, conversations.countByAdvisorIdAndStatusIn(advisorId, HELD_FOR_CAPACITY),
                advisor.getMaxConcurrent());

        Skill skill = skill(skillCode);
        Optional<Conversation> head = conversations.lockNextLiveQueued(skill.getId());
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Conversation conversation = head.get();
        Instant at = micros(now);
        ConversationRules.Assignment assignment = ConversationRules.assign(
                conversation.getStatus(), conversation.getQueuedAt(), at, slaTarget(skill));
        conversation.setAdvisor(advisor);
        conversation.setStatus(ConversationStatus.ASSIGNED);
        conversation.setAssignedAt(at);
        conversation.setWaitSeconds(assignment.waitSeconds());
        conversation.setSlaMet(assignment.slaMet());
        return Optional.of(toRecord(conversation));
    }

    @Override
    @Transactional
    public ConversationRecord close(
            UUID conversationId, ConversationStatus expectedFrom, ConversationStatus target, UUID closedByUserId,
            Instant now) {
        Conversation conversation = lockLive(conversationId);
        ConversationStatus from = conversation.getStatus();
        if (from != expectedFrom) {
            throw new InvalidStateTransitionException(from, target);
        }
        Instant at = micros(now);
        ConversationRules.Closure closure =
                ConversationRules.close(from, target, conversation.getQueuedAt(), conversation.getAssignedAt(), at);

        conversation.setStatus(target);
        conversation.setEndedAt(at);
        if (closure.waitSeconds() != null) {
            conversation.setWaitSeconds(closure.waitSeconds());
            conversation.setSlaMet(
                    ConversationRules.slaMetWhenLeavingQueue(closure.waitSeconds(), slaTarget(conversation.getSkill())));
        }
        conversation.setHandleSeconds(closure.handleSeconds());

        // Whatever state it ends from: an escalation raised before escalating moved the conversation
        // can sit PENDING on an ACTIVE one, and an ended conversation leaves nothing to escalate.
        for (Escalation pending : escalations.findByConversationIdAndStatus(conversationId, EscalationStatus.PENDING)) {
            pending.setStatus(EscalationStatus.RESOLVED);
            pending.setResolvedAt(at);
            // An account deleted since its token was issued resolves anonymously, not with a 500.
            pending.setResolvedBy(closedByUserId == null ? null : users.findById(closedByUserId).orElse(null));
        }
        return toRecord(conversation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationRecord> heldBy(UUID advisorId) {
        return conversations.findLiveHeldBy(advisorId, HELD).stream()
                .map(ConversationLifecycleAdapter::toRecord)
                .toList();
    }

    // ------------------------------------------------------------------ messages

    @Override
    @Transactional
    public PostedMessage post(UUID conversationId, MessageSender sender, String content, Instant now) {
        Conversation conversation = lockLive(conversationId);
        ConversationStatus before = conversation.getStatus();
        ConversationStatus after = switch (sender) {
            case ADVISOR -> ConversationRules.statusAfterAdvisorMessage(before);
            case CUSTOMER -> {
                if (!ConversationRules.acceptsCustomerMessage(before)) {
                    throw InvalidStateTransitionException.noMessagesIn(before, "customer");
                }
                yield before;
            }
            case SYSTEM -> throw new IllegalArgumentException("system messages are not posted through this port");
        };
        conversation.setStatus(after);

        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setContent(content);
        message.setAiGenerated(false);
        message.setSentAt(micros(now));
        Message saved = messages.save(message);
        return new PostedMessage(toRecord(saved), toRecord(conversation), after != before);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageRecord> recent(UUID conversationId, int limit) {
        List<MessageRecord> newestFirst = messages
                .findByConversationIdOrderBySentAtDescIdDesc(conversationId, PageRequest.of(0, limit))
                .stream()
                .map(ConversationLifecycleAdapter::toRecord)
                .toList();
        return newestFirst.reversed();
    }

    // ------------------------------------------------------------------ helpers

    private Conversation lockLive(UUID conversationId) {
        return conversations.findByIdForUpdate(conversationId)
                .filter(c -> c.getRunId() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", conversationId));
    }

    private Skill skill(String code) {
        return skills.findByCode(code).orElseThrow(() -> new ResourceNotFoundException("Skill", code));
    }

    private int slaTarget(Skill skill) {
        return slaPolicies.findFirstBySkillIdAndActiveTrueOrderByTargetSecondsAsc(skill.getId())
                .or(slaPolicies::findFirstBySkillIsNullAndActiveTrueOrderByTargetSecondsAsc)
                .map(SlaPolicy::getTargetSeconds)
                .orElseThrow(() -> new SlaPolicyNotFoundException(skill.getId()));
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** Reads foreign keys through the associations: inside the transaction, no extra round trip escapes. */
    private static ConversationRecord toRecord(Conversation c) {
        Advisor advisor = c.getAdvisor();
        return new ConversationRecord(
                c.getId(),
                c.getCustomer().getId(),
                c.getCustomer().getUser() == null ? null : c.getCustomer().getUser().getId(),
                advisor == null ? null : advisor.getId(),
                advisor == null || advisor.getUser() == null ? null : advisor.getUser().getId(),
                c.getSkill() == null ? null : c.getSkill().getCode(),
                c.getIntent(),
                c.getChannel(),
                c.getStatus(),
                c.getQueuedAt(),
                c.getAssignedAt(),
                c.getEndedAt());
    }

    private static MessageRecord toRecord(Message m) {
        return new MessageRecord(m.getId(), m.getConversation().getId(), m.getSender(), m.getContent(), m.getSentAt());
    }
}
