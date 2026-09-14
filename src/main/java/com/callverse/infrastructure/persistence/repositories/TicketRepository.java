package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Ticket;
import com.callverse.core.domain.enums.TicketStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Its own aggregate root: a ticket outlives the conversation that raised it, which ON DELETE
 * SET NULL on conversation_id states in the schema.
 */
@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    /** Uses idx_ticket_customer (customer_id, created_at DESC). */
    List<Ticket> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Ticket> findByStatusIn(List<TicketStatus> statuses);
}
