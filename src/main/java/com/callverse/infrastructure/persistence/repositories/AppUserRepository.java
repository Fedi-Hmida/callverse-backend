package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Authentication root. Looked up by email during login, which is the only access path that
 * exists before a principal is known.
 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Backed by the unique index on email. */
    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
