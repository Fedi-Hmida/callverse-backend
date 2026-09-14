package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.CommercialCredit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Credits granted to customers, and the audit trail behind the rule the project cares most about.
 */
@Repository
public interface CommercialCreditRepository extends JpaRepository<CommercialCredit, UUID> {

    /**
     * Total already granted to a customer since a given instant.
     *
     * <p>This is what the commercial-credit ceiling is actually checked against when the Customer
     * Advisor agent calls {@code apply_credit}. The ceiling is not a per-grant limit but a rolling
     * total, and enforcing it requires this sum — which is precisely why the backend and not the
     * agent is the authority: an agent cannot know what other conversations have already granted.
     *
     * <p>{@code coalesce} so that a customer with no prior credits returns zero rather than null,
     * removing a null check from every call site that compares against a ceiling.
     */
    @Query("""
           select coalesce(sum(c.amount), 0)
             from CommercialCredit c
            where c.customer.id = :customerId
              and c.createdAt >= :since
           """)
    BigDecimal sumGrantedToCustomerSince(
            @Param("customerId") UUID customerId, @Param("since") Instant since);

    List<CommercialCredit> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    /** Credits that exceeded an advisor's ceiling and required sign-off. The supervisor audit view. */
    List<CommercialCredit> findByApprovedByIsNotNullOrderByCreatedAtDesc();

    List<CommercialCredit> findByConversationId(UUID conversationId);
}
