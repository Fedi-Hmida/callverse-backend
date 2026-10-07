package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.AppUserDirectory;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.infrastructure.persistence.repositories.AppUserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Backs {@link AppUserDirectory} with Spring Data.
 *
 * <p>This class is the whole reason the port exists: {@code AppUserRepository} lives in
 * {@code infrastructure}, and the ArchUnit rule {@code core_must_not_depend_on_outer_layers}
 * forbids the use case from importing it. The dependency is inverted here, in the one place that is
 * allowed to know both sides.
 *
 * <p>Package-private, like {@code PlatformMetadataAdapter}: nothing outside this package should
 * name the adapter, only the port.
 */
@Component
@RequiredArgsConstructor
class AppUserDirectoryAdapter implements AppUserDirectory {

    private final AppUserRepository repository;

    @Override
    public Optional<AppUser> findActiveByEmail(String email) {
        return repository.findByEmailIgnoreCaseAndActiveTrue(email);
    }
}
