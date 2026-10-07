package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.Tickets;
import com.callverse.core.domain.entities.Ticket;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import com.callverse.infrastructure.persistence.repositories.CustomerRepository;
import com.callverse.infrastructure.persistence.repositories.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link Tickets} with Spring Data. The customer and conversation are attached as references
 * — the handler has already proved both exist — so creating a ticket costs one insert.
 */
@Component
@RequiredArgsConstructor
class TicketsAdapter implements Tickets {

    private final TicketRepository tickets;
    private final CustomerRepository customers;
    private final ConversationRepository conversations;

    @Override
    @Transactional
    public OpenedTicket open(NewTicket request) {
        Ticket ticket = new Ticket();
        ticket.setCustomer(customers.getReferenceById(request.customerId()));
        if (request.conversationId() != null) {
            ticket.setConversation(conversations.getReferenceById(request.conversationId()));
        }
        ticket.setCategory(request.category());
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setSeverity((short) request.severity());
        ticket.setStatus(request.status());

        Ticket saved = tickets.save(ticket);
        return new OpenedTicket(
                saved.getId(),
                request.customerId(),
                request.conversationId(),
                saved.getCategory(),
                saved.getTitle(),
                saved.getSeverity(),
                saved.getStatus(),
                saved.getCreatedAt());
    }
}
