package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Escalation;
import com.callverse.core.domain.enums.EscalationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The supervisor queue reads escalations directly rather than through the conversations that
 * raised them.
 */
@Repository
public interface EscalationRepository extends JpaRepository<Escalation, UUID> {

    List<Escalation> findByStatusOrderByCreatedAtAsc(EscalationStatus status);

    List<Escalation> findByConversationId(UUID conversationId);
}
