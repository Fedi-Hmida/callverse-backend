package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.enums.ConversationStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * The conversation, with its row locked ({@code SELECT ... FOR UPDATE}) until the surrounding
     * transaction ends. Used to serialise writes that must see each other — two concurrent
     * escalation retries on one conversation queue here instead of both inserting.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The head of a live skill queue, locked, skipping rows another transaction already holds.
     *
     * <p>Native because {@code SKIP LOCKED} is the point: two advisors taking at the same moment
     * each lock a <em>different</em> conversation instead of the second one blocking on the first
     * and then finding it gone. The filter and the sort follow {@code idx_conv_status_queue}
     * column for column; {@code run_id IS NULL} keeps simulation runs out of the live queue.
     */
    @Query(value = """
           select *
             from conversation
            where status = 'QUEUED'
              and skill_id = :skillId
              and run_id is null
            order by priority_score desc, queued_at asc
            limit 1
              for update skip locked
           """, nativeQuery = true)
    Optional<Conversation> lockNextLiveQueued(@Param("skillId") UUID skillId);

    /**
     * A live conversation with what an ownership check needs already loaded: the customer (whose
     * {@code user_id} decides a CUSTOMER's access), the advisor and the skill.
     */
    @Query("""
           select c
             from Conversation c
             join fetch c.customer
             left join fetch c.advisor
             left join fetch c.skill
            where c.id = :id
              and c.runId is null
           """)
    Optional<Conversation> findLive(@Param("id") UUID id);

    /**
     * What an advisor holds. No index leads with {@code advisor_id} (schema request S-6); at
     * demonstration volume the scan is negligible, and the request stands for production volume.
     */
    @Query("""
           select c
             from Conversation c
             join fetch c.customer
             join fetch c.advisor
             left join fetch c.skill
            where c.advisor.id = :advisorId
              and c.status in :statuses
              and c.runId is null
            order by c.assignedAt asc, c.queuedAt asc
           """)
    List<Conversation> findLiveHeldBy(
            @Param("advisorId") UUID advisorId, @Param("statuses") Collection<ConversationStatus> statuses);

    /** How many conversations an advisor holds now: the {@code max_concurrent} check. */
    long countByAdvisorIdAndStatusIn(UUID advisorId, Collection<ConversationStatus> statuses);

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
