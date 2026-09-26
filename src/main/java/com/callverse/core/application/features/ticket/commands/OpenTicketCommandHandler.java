package com.callverse.core.application.features.ticket.commands;

import com.callverse.core.application.exceptions.ConversationCustomerMismatchException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.core.application.interfaces.ConversationDirectory.ConversationRef;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.Tickets;
import com.callverse.core.application.interfaces.Tickets.NewTicket;
import com.callverse.core.application.interfaces.Tickets.OpenedTicket;
import com.callverse.core.domain.enums.TicketStatus;
import java.util.Objects;

/**
 * Answers the agent tool {@code POST /internal/tickets} ({@code OWNERSHIP_RULES.md} E5).
 *
 * <p><strong>What the backend decides, not the agent.</strong> Every new ticket is {@code OPEN};
 * the agent cannot file one already closed. Severity defaults to 3 and must be 1 to 5 — checked here
 * as well as at the HTTP edge, so another caller of this handler cannot reach the database's CHECK
 * constraint and surface as a 500. When a conversation is named, it must belong to the named
 * customer.
 *
 * <p><strong>What it cannot decide.</strong> With no conversation named, the customer id is taken on
 * the agent's word: the route carries no conversation, and no column records which conversations the
 * agent is handling. That residual trust is the scope of the service key, recorded in E5.
 */
public class OpenTicketCommandHandler {

    static final int DEFAULT_SEVERITY = 3;

    private final CustomerRecords customers;
    private final ConversationDirectory conversations;
    private final Tickets tickets;

    public OpenTicketCommandHandler(
            CustomerRecords customers, ConversationDirectory conversations, Tickets tickets) {
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.tickets = Objects.requireNonNull(tickets, "tickets must not be null");
    }

    public OpenedTicket handle(OpenTicketCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        int severity = command.severity() == null ? DEFAULT_SEVERITY : command.severity();
        if (severity < 1 || severity > 5) {
            throw new InvalidRequestException("severity must be between 1 and 5");
        }
        if (!customers.exists(command.customerId())) {
            throw new ResourceNotFoundException("Customer", command.customerId());
        }
        if (command.conversationId() != null) {
            ConversationRef conversation =
                    conversations
                            .find(command.conversationId())
                            .orElseThrow(() -> new ResourceNotFoundException("Conversation", command.conversationId()));
            if (!conversation.customerId().equals(command.customerId())) {
                throw new ConversationCustomerMismatchException(command.conversationId());
            }
        }
        return tickets.open(new NewTicket(
                command.customerId(),
                command.conversationId(),
                command.category().trim(),
                command.title().trim(),
                command.description(),
                severity,
                TicketStatus.OPEN));
    }
}
