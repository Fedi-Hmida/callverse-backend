package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.enums.UserRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication root. Looked up by email during login, which is the only access path that
 * exists before a principal is known; by id on every authenticated request (revocation) and by the
 * administrator's account routes.
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

    /**
     * Every active administrator, row-locked in id order. An account change locks these first, so two
     * administrators acting at the same instant are serialized in a deterministic order (no deadlock)
     * and the second sees the first one's result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.role = :role and u.active = true order by u.id")
    List<AppUser> lockActiveByRole(@Param("role") UserRole role);

    /** Just what the per-request revocation check needs. */
    interface StatusView {
        Boolean getActive();

        UserRole getRole();
    }

    /**
     * The account's status, as one plain SELECT. {@code SUPPORTS}: joins a caller's transaction if
     * there is one, otherwise runs without opening one — this is read on every authenticated request.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    @Query("select u.active as active, u.role as role from AppUser u where u.id = :id")
    Optional<StatusView> findStatusById(@Param("id") UUID id);

    /** The account, row-locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The administrator's account list. Each filter is optional (null = any). {@code pattern} is a
     * lower-case LIKE pattern whose wildcards the caller has escaped with {@code !}, so a {@code %}
     * typed by the user is a literal.
     */
    @Query(value = """
           select u
             from AppUser u
            where (:role is null or u.role = :role)
              and (:active is null or u.active = :active)
              and (:pattern is null
                   or lower(u.email) like :pattern escape '!'
                   or lower(u.firstName) like :pattern escape '!'
                   or lower(u.lastName) like :pattern escape '!')
            order by u.createdAt desc, u.id
           """)
    Page<AppUser> search(
            @Param("role") UserRole role,
            @Param("active") Boolean active,
            @Param("pattern") String pattern,
            Pageable pageable);
}
