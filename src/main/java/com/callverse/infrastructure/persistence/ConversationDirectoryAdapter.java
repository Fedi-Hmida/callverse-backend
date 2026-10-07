package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Backs {@link ConversationDirectory} with Spring Data. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ConversationDirectoryAdapter implements ConversationDirectory {

    private final ConversationRepository repository;

    @Override
    public Optional<ConversationRef> find(UUID conversationId) {
        // getCustomer().getId() reads the foreign key without loading the customer.
        // Live conversations only: a simulation run's conversations are never acted on from these routes.
        return repository
                .findById(conversationId)
                .filter(c -> c.getRunId() == null)
                .map(c -> new ConversationRef(c.getId(), c.getCustomer().getId(), c.getStatus(),
                        c.getAdvisor() == null || c.getAdvisor().getUser() == null
                                ? null
                                : c.getAdvisor().getUser().getId()));
    }
}
