package com.callverse.core.application.features.conversation;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.UserRole;
import java.util.Optional;
import java.util.UUID;

/**
 * Who may do what to one conversation. <strong>One policy, two callers:</strong> the REST use cases
 * and the STOMP subscription check both ask it, so the HTTP route and the live topic can never
 * disagree (ownership rules A15 and B14 exist because they usually do).
 *
 * <ul>
 *   <li><strong>CUSTOMER</strong> — the conversation's customer is linked to this login
 *       ({@code customer.user_id}, rules A6, A8, A10).
 *   <li><strong>ADVISOR</strong> — the conversation is assigned to the advisor linked to this login
 *       ({@code advisor.user_id}, rules B5, B8). A queued conversation is owned by nobody: an
 *       advisor reaches it only by taking it from the queue.
 *   <li><strong>SUPERVISOR, ADMIN</strong> — every live conversation (rule C7: the schema has no
 *       team to narrow a supervisor to).
 * </ul>
 */
public final class ConversationAccessPolicy {

    private ConversationAccessPolicy() {
        // Pure functions; never instantiated.
    }

    public static boolean canRead(AuthenticatedPrincipal caller, ConversationRecord conversation) {
        return switch (caller.role()) {
            case SUPERVISOR, ADMIN -> true;
            case ADVISOR -> isAssignedAdvisor(caller, conversation);
            case CUSTOMER -> isOwningCustomer(caller, conversation);
        };
    }

    /**
     * The sender a caller writes as, decided here and never read from a request (rule A10). Only the
     * two parties to the conversation write; a supervisor reads.
     */
    public static Optional<MessageSender> senderFor(AuthenticatedPrincipal caller, ConversationRecord conversation) {
        if (caller.role() == UserRole.ADVISOR && isAssignedAdvisor(caller, conversation)) {
            return Optional.of(MessageSender.ADVISOR);
        }
        if (caller.role() == UserRole.CUSTOMER && isOwningCustomer(caller, conversation)) {
            return Optional.of(MessageSender.CUSTOMER);
        }
        return Optional.empty();
    }

    /**
     * An escalated conversation belongs to the supervisors and only they resolve it; otherwise the
     * assigned advisor does.
     */
    public static boolean canResolve(AuthenticatedPrincipal caller, ConversationRecord conversation) {
        if (conversation.status() == ConversationStatus.ESCALATED) {
            return caller.role() == UserRole.SUPERVISOR;
        }
        return caller.role() == UserRole.ADVISOR && isAssignedAdvisor(caller, conversation);
    }

    /** The customer who left, the advisor who saw them leave, or operations staff. */
    public static boolean canAbandon(AuthenticatedPrincipal caller, ConversationRecord conversation) {
        return switch (caller.role()) {
            case SUPERVISOR, ADMIN -> true;
            case ADVISOR -> isAssignedAdvisor(caller, conversation);
            case CUSTOMER -> isOwningCustomer(caller, conversation);
        };
    }

    private static boolean isAssignedAdvisor(AuthenticatedPrincipal caller, ConversationRecord conversation) {
        return same(caller.userId(), conversation.advisorUserId());
    }

    private static boolean isOwningCustomer(AuthenticatedPrincipal caller, ConversationRecord conversation) {
        return same(caller.userId(), conversation.customerUserId());
    }

    /** Null never matches: an unassigned conversation or an unlinked customer is owned by nobody. */
    private static boolean same(UUID caller, UUID owner) {
        return owner != null && owner.equals(caller);
    }
}
