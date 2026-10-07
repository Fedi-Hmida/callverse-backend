package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.AdvisorProfileNotFoundException;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.AdvisorDirectory;
import com.callverse.core.application.interfaces.AdvisorDirectory.AdvisorProfile;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * An advisor takes the head of a skill queue: the highest priority score, then the longest wait.
 *
 * <p>Serves {@code POST /api/v1/queues/{skill}/next} (advisors). <strong>A pull, not a pick</strong>:
 * an advisor cannot choose which customer to serve, so the queue's order is the only order there
 * is, and two advisors pulling at the same moment get two different conversations (the adapter
 * takes the head with {@code SKIP LOCKED}).
 *
 * <ul>
 *   <li>The caller must have an advisor profile, else {@code ADVISOR_PROFILE_NOT_FOUND}.
 *   <li>They must hold the skill (ownership rule B6), else 403.
 *   <li>They must be below {@code max_concurrent}, else 409 {@code ADVISOR_UNAVAILABLE}.
 * </ul>
 *
 * <p>Wait and SLA are measured at this transition and stored; nothing recomputes them later.
 *
 * <p><strong>Advisor presence is not consulted yet.</strong> {@code advisor.status} (available,
 * break, offline) has no route to change it, so taking work is gated by skill and capacity alone;
 * presence arrives with the supervisor's floor view.
 */
public class TakeNextConversationCommandHandler {

    private final AdvisorDirectory advisors;
    private final ConversationLifecycle conversations;
    private final ConversationEvents events;
    private final Clock clock;

    public TakeNextConversationCommandHandler(
            AdvisorDirectory advisors, ConversationLifecycle conversations, ConversationEvents events, Clock clock) {
        this.advisors = Objects.requireNonNull(advisors, "advisors must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** @return the conversation now assigned to the caller, or empty when the queue is empty */
    public Optional<ConversationRecord> handle(TakeNextConversationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AdvisorProfile advisor = advisors.findByUserId(command.caller().userId())
                .orElseThrow(() -> new AdvisorProfileNotFoundException(command.caller().userId()));
        if (!advisor.holds(command.skill())) {
            throw new ActionNotPermittedException("You hold no skill for this queue.");
        }
        Optional<ConversationRecord> taken = conversations.assignNext(advisor.id(), command.skill(), clock.instant());
        taken.ifPresent(events::leftQueue);
        return taken;
    }
}
