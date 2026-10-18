package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable support follow-up and auditable goodwill credits. Implementations enforce ownership under lock. */
public interface SupportOperations {
    TicketPage tickets(AuthenticatedPrincipal caller, UUID customerId, TicketStatus status, int page);
    TicketView ticket(AuthenticatedPrincipal caller, UUID id);
    TicketView transition(AuthenticatedPrincipal caller, UUID id, TicketStatus expected, TicketStatus target);
    Comment comment(AuthenticatedPrincipal caller, UUID id, String content);
    List<Credit> credits(AuthenticatedPrincipal caller, UUID customerId);
    Credit grant(AuthenticatedPrincipal caller, CreditRequest request);
    /** Internal service has no human approval authority; conversation determines advisor and ceiling. */
    Credit grantFromService(CreditRequest request);

    record TicketPage(List<TicketView> content, int page, int size, boolean hasNext) {}
    record TicketView(UUID id, UUID customerId, UUID conversationId, String customerName, String title,
                      String description, String category, int severity, TicketStatus status,
                      Instant createdAt, Instant resolvedAt, List<Comment> comments) {}
    record Comment(UUID id, UUID ticketId, UUID authorId, String authorRole, String authorName,
                   String content, Instant createdAt) {}
    record CreditRequest(UUID customerId, UUID conversationId, UUID accountId, BigDecimal amount,
                         String reason, UUID requestId) {}
    record Credit(UUID id, UUID customerId, UUID conversationId, UUID accountId, UUID transactionId,
                  BigDecimal amount, String reason, UUID grantedBy, UUID approvedBy, UUID requestId,
                  Instant createdAt) {}
}
