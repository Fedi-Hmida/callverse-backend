package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ConversationAlreadyOpenException;
import com.callverse.core.application.exceptions.ConversationUnderSupervisionException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.exceptions.SlaPolicyNotFoundException;
import com.callverse.core.application.interfaces.CustomerConversationRemoval;
import com.callverse.core.application.interfaces.AdvisorAgent.Source;
import com.callverse.core.application.interfaces.AdvisorAgent.ToolCall;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.callverse.infrastructure.persistence.repositories.AdvisorRepository;
import com.callverse.infrastructure.persistence.repositories.AppUserRepository;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import com.callverse.infrastructure.persistence.repositories.CustomerRepository;
import com.callverse.infrastructure.persistence.repositories.EscalationRepository;
import com.callverse.infrastructure.persistence.repositories.MessageRepository;
import com.callverse.infrastructure.persistence.repositories.SkillRepository;
import com.callverse.infrastructure.persistence.repositories.SlaPolicyRepository;
import com.callverse.infrastructure.persistence.repositories.RoutingRuleRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
class ConversationLifecycleAdapter implements ConversationLifecycle, ConversationMessages, CustomerConversationRemoval {

    /** What counts against {@code max_concurrent}: an escalated conversation is the supervisor's. */
    private static final Set<ConversationStatus> HELD_FOR_CAPACITY =
            EnumSet.of(ConversationStatus.ASSIGNED, ConversationStatus.ACTIVE);

    private static final Set<ConversationStatus> HELD =
            EnumSet.of(ConversationStatus.ASSIGNED, ConversationStatus.ACTIVE, ConversationStatus.ESCALATED);

    /** Not ended: a customer has at most one of these at a time. */
    private static final Set<ConversationStatus> LIVE = EnumSet.of(ConversationStatus.QUEUED,
            ConversationStatus.ASSIGNED, ConversationStatus.ACTIVE, ConversationStatus.ESCALATED);

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final CustomerRepository customers;
    private final SkillRepository skills;
    private final AdvisorRepository advisors;
    private final SlaPolicyRepository slaPolicies;
    private final EscalationRepository escalations;
    private final AppUserRepository users;
    private final ObjectMapper objectMapper;
    private final RoutingRuleRepository routingRules;

    // ------------------------------------------------------------------ lifecycle

    @Override
    @Transactional
    public ConversationRecord open(UUID customerId, String skillCode, Intent intent, Channel channel, Instant now) {
        // The customer row is the lock for "one live conversation": a second open waits here, then
        // sees the first. It locks no conversation, so the advisor-then-conversation order is kept.
        Customer customer = customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
        conversations.findLiveOf(customerId, LIVE).stream().findFirst().ifPresent(open -> {
            throw new ConversationAlreadyOpenException(open);
        });
        Skill requestedSkill = skill(skillCode);
        // Intent-only rules are the supported runtime language. Never treat an unknown JSON
        // condition as true; existing advanced predicates remain inactive until implemented.
        Skill skill = routingRules.findByActiveTrueOrderByPriorityAscIdAsc().stream()
                .filter(rule -> rule.getIntent() == null || rule.getIntent() == intent)
                .filter(rule -> rule.getConditions() == null || rule.getConditions().isNull()
                        || (rule.getConditions().isObject() && rule.getConditions().isEmpty()))
                .filter(rule -> rule.getSkill() != null)
                .map(com.callverse.core.domain.entities.RoutingRule::getSkill)
                .findFirst().orElse(requestedSkill);

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
        // Capabilities can change after the use case's initial read. Recheck under the same row
        // lock used by profile administration so a removed skill cannot receive a new contact.
        if (advisor.getSkills().stream().noneMatch(link -> link.getSkill().getCode().equals(skillCode))) {
            throw new com.callverse.core.application.exceptions.ActionNotPermittedException("You hold no skill for this queue.");
        }
        if (advisor.getStatus() != com.callverse.core.domain.enums.AdvisorStatus.AVAILABLE
                && advisor.getStatus() != com.callverse.core.domain.enums.AdvisorStatus.BUSY) {
            throw new com.callverse.core.domain.exceptions.AdvisorUnavailableException(advisorId, advisor.getStatus());
        }
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
    @Transactional
    public Removal remove(UUID conversationId, UUID customerUserId, String notice, Instant now) {
        Conversation conversation = lockLive(conversationId);
        Customer owner = conversation.getCustomer();
        if (owner.getUser() == null || !owner.getUser().getId().equals(customerUserId)
                || conversation.getCustomerDeletedAt() != null) {
            throw new ResourceNotFoundException("Conversation", conversationId);
        }
        ConversationStatus before = conversation.getStatus();
        if (before == ConversationStatus.ESCALATED) {
            throw new ConversationUnderSupervisionException(conversationId);
        }
        if (LIVE.contains(before)) {
            close(conversationId, before, ConversationStatus.ABANDONED, customerUserId, now);
        }
        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(MessageSender.SYSTEM);
        message.setContent(notice);
        message.setAiGenerated(false);
        message.setSentAt(micros(now));
        Message saved = messages.save(message);
        conversation.setCustomerDeletedAt(micros(now));
        return new Removal(before, toRecord(conversation), toRecord(saved));
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
    @Transactional
    public Optional<PostedMessage> postAgentTurn(
            UUID conversationId,
            long triggeringCustomerMessageId,
            String content,
            List<Source> sources,
            List<ToolCall> toolCalls,
            Instant now) {
        Conversation conversation = lockLive(conversationId);
        ConversationStatus before = conversation.getStatus();
        if (before != ConversationStatus.ASSIGNED && before != ConversationStatus.ACTIVE) {
            return Optional.empty();
        }

        Message latest = messages.findByConversationIdOrderBySentAtDescIdDesc(
                        conversationId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
        if (latest == null
                || !latest.getId().equals(triggeringCustomerMessageId)
                || latest.getSender() != MessageSender.CUSTOMER) {
            return Optional.empty();
        }

        ConversationStatus after = ConversationRules.statusAfterAdvisorMessage(before);
        conversation.setStatus(after);
        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(MessageSender.ADVISOR);
        message.setContent(content);
        message.setAiGenerated(true);
        message.setSources(objectMapper.valueToTree(sources.stream()
                .map(source -> Map.of(
                        "kb_article_id", source.knowledgeBaseArticleId().toString(),
                        "score", source.score()))
                .toList()));
        message.setToolCalls(objectMapper.valueToTree(toolCalls.stream()
                .map(toolCall -> Map.of(
                        "tool", toolCall.tool(),
                        "args", toolCall.args(),
                        "result_summary", toolCall.resultSummary(),
                        "ok", toolCall.ok()))
                .toList()));
        message.setSentAt(micros(now));
        Message saved = messages.save(message);
        return Optional.of(new PostedMessage(toRecord(saved), toRecord(conversation), after != before));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean humanAdvisorHasReplied(UUID conversationId) {
        return messages.existsByConversationIdAndSenderAndAiGeneratedFalse(conversationId, MessageSender.ADVISOR);
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
                // Removed by the customer: no customer may read it any more; staff still can.
                c.getCustomer().getUser() == null || c.getCustomerDeletedAt() != null
                        ? null : c.getCustomer().getUser().getId(),
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
        return new MessageRecord(
                m.getId(), m.getConversation().getId(), m.getSender(), m.getContent(), m.getSentAt(), m.isAiGenerated());
    }
}
