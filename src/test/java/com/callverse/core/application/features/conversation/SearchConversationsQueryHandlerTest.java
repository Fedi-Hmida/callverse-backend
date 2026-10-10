package com.callverse.core.application.features.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.features.conversation.queries.SearchConversationsQuery;
import com.callverse.core.application.features.conversation.queries.SearchConversationsQueryHandler;
import com.callverse.core.application.interfaces.ConversationSupervision;
import com.callverse.core.application.interfaces.ConversationSupervision.ConversationPage;
import com.callverse.core.application.interfaces.ConversationSupervision.ConversationSearch;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The supervisor's search: what it accepts and what it hands the port. Plain unit tests. */
class SearchConversationsQueryHandlerTest {

    private final AtomicReference<ConversationSearch> seen = new AtomicReference<>();
    private final ConversationSupervision port = (search, page, size) -> {
        seen.set(search);
        return new ConversationPage(List.of(), page, size, 0, 0);
    };
    private final SearchConversationsQueryHandler handler = new SearchConversationsQueryHandler(port);

    private static SearchConversationsQuery query(String q, Instant from, Instant to, int page, int size) {
        return new SearchConversationsQuery(null, null, null, null, q, from, to, page, size);
    }

    @Test
    @DisplayName("every filter reaches the port; the skill is matched in upper case")
    void passThrough() {
        java.util.UUID customer = java.util.UUID.randomUUID();
        java.util.UUID advisor = java.util.UUID.randomUUID();
        Instant from = Instant.parse("2026-10-10T08:00:00Z");
        Instant to = Instant.parse("2026-10-10T18:00:00Z");
        handler.handle(new SearchConversationsQuery(
                java.util.Set.of(com.callverse.core.domain.enums.ConversationStatus.ESCALATED), " fraud ", customer,
                advisor, null, from, to, 1, 25));
        ConversationSearch s = seen.get();
        assertThat(s.statuses()).containsExactly(com.callverse.core.domain.enums.ConversationStatus.ESCALATED);
        assertThat(s.skill()).isEqualTo("FRAUD");
        assertThat(s.customerId()).isEqualTo(customer);
        assertThat(s.advisorId()).isEqualTo(advisor);
        assertThat(s.from()).isEqualTo(from);
        assertThat(s.to()).isEqualTo(to);
    }

    @Test
    @DisplayName("size 1 to 100, page from 0: anything else is VALIDATION_FAILED and never reaches the port")
    void bounds() {
        handler.handle(query(null, null, null, 0, 100));
        assertThatThrownBy(() -> handler.handle(query(null, null, null, 0, 101))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(query(null, null, null, 0, 0))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> handler.handle(query(null, null, null, -1, 20))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("a window that ends before it starts is refused; an empty one is not")
    void window() {
        Instant t = Instant.parse("2026-10-10T09:00:00Z");
        handler.handle(query(null, t, t, 0, 20));
        assertThatThrownBy(() -> handler.handle(query(null, t, t.minusSeconds(1), 0, 20)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("the text is trimmed, a blank one means no text filter, and more than 100 characters is refused")
    void text() {
        handler.handle(query("  amina  ", null, null, 0, 20));
        assertThat(seen.get().query()).isEqualTo("amina");
        handler.handle(query("   ", null, null, 0, 20));
        assertThat(seen.get().query()).isNull();
        assertThatThrownBy(() -> handler.handle(query("x".repeat(101), null, null, 0, 20)))
                .isInstanceOf(InvalidRequestException.class);
    }
}
