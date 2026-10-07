package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Card;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Card lifecycle. Read with a customer's profile; the only write today is blocking, under a row lock. */
@Repository
public interface CardRepository extends JpaRepository<Card, UUID> {

    /** The cards of a customer's accounts, in one query for the whole profile. */
    List<Card> findByAccountIdIn(Collection<UUID> accountIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.id = :id")
    Optional<Card> findByIdForUpdate(@Param("id") UUID id);
}
