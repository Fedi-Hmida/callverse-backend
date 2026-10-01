package com.callverse.core.application.features.customer.queries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.application.interfaces.TransactionSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bounds and the unknown-customer rule, against a hand-written fake port. */
class GetRecentTransactionsQueryHandlerTest {

    private static final UUID KNOWN = UUID.randomUUID();

    /** Records the limit it was asked for; knows exactly one customer. */
    private static final class FakeRecords implements CustomerRecords {
        Integer askedLimit;

        @Override
        public Optional<CustomerProfile> findProfile(UUID customerId) {
            return Optional.empty();
        }

        @Override
        public boolean exists(UUID customerId) {
            return KNOWN.equals(customerId);
        }

        @Override
        public List<TransactionSummary> findRecentTransactions(UUID customerId, int limit) {
            askedLimit = limit;
            return List.of();
        }
    }

    private final FakeRecords records = new FakeRecords();
    private final GetRecentTransactionsQueryHandler handler = new GetRecentTransactionsQueryHandler(records);

    @Test
    @DisplayName("no count means ten")
    void defaultsToTen() {
        handler.handle(new GetRecentTransactionsQuery(KNOWN, null));
        assertThat(records.askedLimit).isEqualTo(10);
    }

    @Test
    @DisplayName("fifty is the ceiling, and fifty-one is refused")
    void boundsAreEnforcedAtTheEdges() {
        handler.handle(new GetRecentTransactionsQuery(KNOWN, 50));
        assertThat(records.askedLimit).isEqualTo(50);

        assertThatThrownBy(() -> handler.handle(new GetRecentTransactionsQuery(KNOWN, 51)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(new GetRecentTransactionsQuery(KNOWN, 0)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("an unknown customer is a 404, never an empty history")
    void unknownCustomerIsNotFound() {
        assertThatThrownBy(() -> handler.handle(new GetRecentTransactionsQuery(UUID.randomUUID(), 5)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(records.askedLimit).as("no query for an unknown customer").isNull();
    }
}
