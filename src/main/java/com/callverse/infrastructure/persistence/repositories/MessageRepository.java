package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Message;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Transcript access. Message sits inside the Conversation aggregate conceptually, but gets its own
 * repository because it is the highest-volume table in the schema: loading a conversation in order
 * to append one message, or to count its messages, is exactly the N+1 that omitting a collection on
 * {@code Conversation} was meant to prevent.
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Uses idx_message_conv (conversation_id, sent_at). */
    List<Message> findByConversationIdOrderBySentAtAsc(UUID conversationId);

    /** Paginated for long transcripts; the same index serves it. */
    Page<Message> findByConversationIdOrderBySentAtAsc(UUID conversationId, Pageable pageable);

    long countByConversationId(UUID conversationId);

    /**
     * Agent turns only. Feeds the Quality Analyst, which scores what the AI said rather than what
     * the customer said.
     */
    List<Message> findByConversationIdAndAiGeneratedTrueOrderBySentAtAsc(UUID conversationId);
}
