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

    /**
     * The login lookup. Backed by the unique index on email.
     *
     * <p><strong>Use this, not {@link #findByEmailIgnoreCase}, for authentication.</strong>
     * {@code V1__init.sql:45} records that inactive users are never routed to or authenticated, and
     * that rule has no other enforcement point: the column has no CHECK, the filter chain does not
     * know about it, and nothing else in the request path consults it. Loading by email alone and
     * remembering to test {@code active} afterwards is the shape of the bug this method exists to
     * make impossible.
     */
    Optional<AppUser> findByEmailIgnoreCaseAndActiveTrue(String email);

    /** Backed by the unique index on email. Does not consider {@code active}. */
    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
