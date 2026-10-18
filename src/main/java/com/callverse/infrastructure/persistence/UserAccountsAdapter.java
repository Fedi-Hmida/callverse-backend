package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.AdvisorAdministration;
import com.callverse.core.application.interfaces.UserAccounts;
import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.entities.AdvisorSkill;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.AdvisorStatus;
import com.callverse.core.domain.exceptions.AdvisorConfigurationException;
import com.callverse.core.domain.exceptions.CustomerProfileConfigurationException;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.exceptions.EmailAlreadyUsedException;
import com.callverse.core.domain.services.AccessRules;
import com.callverse.infrastructure.persistence.repositories.AppUserRepository;
import com.callverse.infrastructure.persistence.repositories.AdvisorRepository;
import com.callverse.infrastructure.persistence.repositories.SkillRepository;
import com.callverse.infrastructure.persistence.repositories.CustomerRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link UserAccounts} with Spring Data.
 *
 * <p><strong>Lock order: the active administrators (in id order), then the target.</strong> Every
 * change takes the same order, so concurrent changes queue instead of deadlocking, and the actor's
 * own status is read under the lock — an administrator removed a moment ago is refused, not obeyed.
 */
@Component
@RequiredArgsConstructor
class UserAccountsAdapter implements UserAccounts, AdvisorAdministration {

    /** PostgreSQL's default name for {@code app_user.email UNIQUE} ({@code V1__init.sql}). */
    private static final String EMAIL_UNIQUE_INDEX = "app_user_email_key";

    private final AppUserRepository users;
    private final Clock clock;
    private final AdvisorRepository advisors;
    private final SkillRepository skills;
    private final EntityManager entityManager;
    private final CustomerRepository customers;

    @Override
    @Transactional(readOnly = true)
    public AccountPage list(AccountFilter filter, int page, int size) {
        Page<AppUser> found = users.search(filter.role(), filter.active(), pattern(filter.query()),
                PageRequest.of(page, size));
        return new AccountPage(found.map(UserAccountsAdapter::toRecord).getContent(), page, size,
                found.getTotalElements(), found.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountRecord> find(UUID id) {
        return users.findById(id).map(UserAccountsAdapter::toRecord);
    }

    /**
     * Read on every authenticated request: two columns, no entity, no read-only transaction around
     * it (which would cost a SET plus BEGIN and COMMIT round trips to Neon each time).
     */
    @Override
    public Optional<AccountStatus> status(UUID id) {
        return users.findStatusById(id).map(s -> new AccountStatus(s.getActive(), s.getRole()));
    }

    @Override
    @Transactional
    public AccountRecord create(UUID actorId, NewAccount account) {
        requireActiveAdmin(actorId, users.lockActiveByRole(UserRole.ADMIN));
        if (users.existsByEmailIgnoreCase(account.email())) {
            throw new EmailAlreadyUsedException();
        }
        AppUser user = new AppUser();
        user.setEmail(account.email());
        user.setFirstName(account.firstName());
        user.setLastName(account.lastName());
        user.setRole(account.role());
        user.setPasswordHash(account.passwordHash());
        user.setActive(true);
        // Microseconds: PostgreSQL's precision, so the record returned equals the row read back.
        user.setCreatedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        try {
            users.saveAndFlush(user);
            if (user.getRole() == UserRole.ADVISOR) ensureAdvisor(user);
            if (user.getRole() == UserRole.CUSTOMER) ensureCustomer(user);
            return toRecord(user);
        } catch (DataIntegrityViolationException violation) {
            // Two creations of the same address at once: the unique index decides, the loser is told.
            // Any other constraint is a real error and is not disguised as a taken address.
            if (String.valueOf(violation.getMostSpecificCause().getMessage()).contains(EMAIL_UNIQUE_INDEX)) {
                throw new EmailAlreadyUsedException();
            }
            throw violation;
        }
    }

    @Override
    @Transactional
    public AccountChange changeRole(UUID actorId, UUID targetId, UserRole role) {
        return change(actorId, targetId, u -> u.getRole() == role, u -> {
            u.setRole(role);
            if (role == UserRole.ADVISOR) ensureAdvisor(u);
            if (role == UserRole.CUSTOMER) ensureCustomer(u);
        });
    }

    @Override
    @Transactional
    public AccountChange setActive(UUID actorId, UUID targetId, boolean active) {
        return change(actorId, targetId, u -> u.isActive() == active, u -> u.setActive(active));
    }

    private AccountChange change(
            UUID actorId, UUID targetId, Predicate<AppUser> alreadyThere, Consumer<AppUser> apply) {
        requireActiveAdmin(actorId, users.lockActiveByRole(UserRole.ADMIN));
        AccessRules.requireNotSelf(actorId, targetId);
        AppUser target = users.findByIdForUpdate(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("User", targetId));
        if (alreadyThere.test(target)) {
            // Repeating ADVISOR also repairs accounts created before profile provisioning existed.
            if (target.getRole() == UserRole.ADVISOR) ensureAdvisor(target);
            if (target.getRole() == UserRole.CUSTOMER) ensureCustomer(target);
            return new AccountChange(toRecord(target), false);
        }
        apply.accept(target);
        return new AccountChange(toRecord(target), true);
    }

    @Override
    @Transactional(readOnly = true)
    public Profile profile(UUID actorId, UUID userId) {
        if (users.findStatusById(actorId).filter(s -> s.getActive() && s.getRole() == UserRole.ADMIN).isEmpty()) {
            throw new ActionNotPermittedException("Your administrator access is no longer active.");
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        requireAdvisorRole(user);
        List<Advisor> linked = advisors.findRealByUserId(userId);
        if (linked.isEmpty()) throw new ResourceNotFoundException("Advisor profile", userId);
        if (linked.size() != 1) throw duplicateProfile();
        return profileOf(linked.getFirst());
    }

    @Override
    @Transactional
    public Profile configure(UUID actorId, UUID userId, Configuration configuration) {
        requireActiveAdmin(actorId, users.lockActiveByRole(UserRole.ADMIN));
        AppUser user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        requireAdvisorRole(user);
        Advisor advisor = ensureAdvisor(user);
        advisor = advisors.findByIdForUpdate(advisor.getId()).orElseThrow();
        // Resolve everything before changing anything: an unknown skill rolls back the whole request.
        var requested = new TreeMap<String, com.callverse.core.domain.entities.Skill>();
        for (String code : configuration.skills().keySet()) {
            requested.put(code, skills.findByCode(code)
                    .orElseThrow(() -> new InvalidRequestException("Unknown skill code: " + code)));
        }
        for (AdvisorSkill existing : new ArrayList<>(advisor.getSkills())) {
            String code = existing.getSkill().getCode();
            if (!requested.containsKey(code)) {
                advisor.getSkills().remove(existing);
                entityManager.remove(existing);
            } else {
                existing.setLevel(configuration.skills().get(code).shortValue());
                requested.remove(code);
            }
        }
        for (var skill : requested.entrySet()) {
            AdvisorSkill link = new AdvisorSkill();
            link.setAdvisor(advisor);
            link.setSkill(skill.getValue());
            link.setLevel(configuration.skills().get(skill.getKey()).shortValue());
            entityManager.persist(link);
            advisor.getSkills().add(link);
        }
        advisor.setStatus(configuration.status());
        advisor.setMaxConcurrent(configuration.maxConcurrent());
        entityManager.flush();
        return profileOf(advisor);
    }

    /** Caller holds the user/admin locks, so concurrent creations/promotions cannot duplicate a profile. */
    private Advisor ensureAdvisor(AppUser user) {
        List<Advisor> linked = advisors.findRealByUserId(user.getId());
        if (linked.size() > 1) throw duplicateProfile();
        if (!linked.isEmpty()) return linked.getFirst();
        Advisor advisor = new Advisor();
        advisor.setUser(user);
        String name = user.getFirstName() + " " + user.getLastName();
        advisor.setDisplayName(name.length() > 120 ? name.substring(0, 120) : name);
        advisor.setStatus(AdvisorStatus.OFFLINE);
        advisor.setMaxConcurrent(1);
        advisor.setCreditLimit(BigDecimal.ZERO);
        advisor.setSimulated(false);
        return advisors.saveAndFlush(advisor);
    }

    /** A login owns a contact profile, not an invented bank account, card or balance. */
    private void ensureCustomer(AppUser user) {
        var linked = customers.findByUserIdAndSimulatedFalse(user.getId());
        if (linked.size() > 1) throw new CustomerProfileConfigurationException();
        if (!linked.isEmpty()) return;
        Customer customer = new Customer();
        customer.setUser(user);
        customer.setExternalRef("C-" + UUID.randomUUID().toString().replace("-", ""));
        customer.setFirstName(user.getFirstName());
        customer.setLastName(user.getLastName());
        customer.setRegion("Non renseignee");
        customer.setCreatedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        customers.saveAndFlush(customer);
    }

    private static void requireAdvisorRole(AppUser user) {
        if (user.getRole() != UserRole.ADVISOR) {
            throw new AdvisorConfigurationException("Only an ADVISOR account has staffing capabilities.");
        }
    }

    private static AdvisorConfigurationException duplicateProfile() {
        return new AdvisorConfigurationException("Multiple real profiles are linked to this login; repair the duplicate first.");
    }

    private static Profile profileOf(Advisor advisor) {
        Map<String, Integer> levels = new TreeMap<>();
        advisor.getSkills().forEach(skill -> levels.put(skill.getSkill().getCode(), (int) skill.getLevel()));
        return new Profile(advisor.getId(), advisor.getUser().getId(), advisor.getDisplayName(), advisor.getStatus(),
                advisor.getMaxConcurrent(), advisor.getCreditLimit(), Map.copyOf(levels));
    }

    /**
     * The actor must be among the active administrators just locked: an administrator blocked or
     * demoted a moment ago is refused, whatever their token said when the request began.
     */
    private static void requireActiveAdmin(UUID actorId, List<AppUser> lockedActiveAdmins) {
        if (lockedActiveAdmins.stream().noneMatch(a -> a.getId().equals(actorId))) {
            throw new ActionNotPermittedException("Your administrator access is no longer active.");
        }
    }

    /** Lower-case LIKE pattern with the user's wildcards escaped, or null for "any". */
    private static String pattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String escaped = query.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static AccountRecord toRecord(AppUser u) {
        return new AccountRecord(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getRole(),
                u.isActive(), u.getCreatedAt());
    }
}
