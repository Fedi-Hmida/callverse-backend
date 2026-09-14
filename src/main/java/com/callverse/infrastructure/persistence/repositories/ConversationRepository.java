package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.enums.ConversationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The core aggregate root's repository. Carries the single most performance-critical query in the
 * application.
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * "Next conversation to assign" — the query the whole queue engine turns on, executed every time
     * an advisor becomes available.
     *
     * <p><strong>Index usage.</strong> It is written to match {@code idx_conv_status_queue
     * (status, skill_id, priority_score DESC)} column for column: equality on {@code status},
     * equality on {@code skill_id}, then the index's own descending order on
     * {@code priority_score}. PostgreSQL can therefore satisfy filter and primary sort from one
     * index scan and stop as soon as {@code Pageable} is satisfied, rather than sorting the whole
     * queue.
     *
     * <p><strong>The tiebreak costs something, deliberately.</strong> {@code queuedAt ASC} is not in
     * the index, so conversations sharing a priority score are sorted after the index scan. That is
     * a small in-memory sort over one priority group, and it buys FIFO fairness within a priority
     * band — which matters because STATIC_FIFO is the experimental baseline, and a baseline that
     * broke ties arbitrarily would not be reproducible across seeds.
     *
     * <p>{@code c.skill.id} reads the foreign key column directly and emits no join.
     *
     * @param pageable use {@code PageRequest.of(0, 1)} to take only the head of the queue
     */
    @Query("""
           select c
             from Conversation c
            where c.status = :status
              and c.skill.id = :skillId
            order by c.priorityScore desc, c.queuedAt asc
           """)
    List<Conversation> findNextToAssign(
            @Param("status") ConversationStatus status,
            @Param("skillId") UUID skillId,
            Pageable pageable);

    /** Queue depth per skill, the Workforce Manager's primary observation. */
    long countByStatusAndSkillId(ConversationStatus status, UUID skillId);

    /** Uses idx_conv_customer (customer_id, queued_at DESC). */
    List<Conversation> findByCustomerIdOrderByQueuedAtDesc(UUID customerId);

    /**
     * Uses idx_conv_run, which is partial on {@code run_id IS NOT NULL} — so this is efficient for a
     * simulation run and the index deliberately does not carry the live conversations at all.
     */
    List<Conversation> findByRunId(UUID runId);

    /**
     * Live conversations only. {@code runId IS NULL} is the pivot between the two universes, so this
     * is what business reporting must use rather than {@code findAll}.
     */
    List<Conversation> findByRunIdIsNullAndStatus(ConversationStatus status);
}
