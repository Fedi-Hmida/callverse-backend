package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.ConversationSupervision;
import com.callverse.core.application.interfaces.ConversationSupervision.ConversationPage;
import com.callverse.core.application.interfaces.ConversationSupervision.ConversationSearch;
import java.util.Objects;

/**
 * Lists live conversations for supervision: by customer, advisor, status, skill, date or text,
 * newest first, a page at a time.
 *
 * <p>Serves {@code GET /api/v1/supervision/conversations} (SUPERVISOR, ADMIN). It answers the
 * question the per-conversation routes cannot: <em>which</em> conversations exist. A supervisor then
 * opens one with {@code GET /conversations/{id}}, {@code .../messages} and its live topic, which the
 * access policy already lets them read. Advisors keep their own list ({@code /conversations/mine});
 * a customer has none here.
 */
public class SearchConversationsQueryHandler {

    public static final int MAX_SIZE = 100;
    public static final int MAX_QUERY_LENGTH = 100;

    private final ConversationSupervision supervision;

    public SearchConversationsQueryHandler(ConversationSupervision supervision) {
        this.supervision = Objects.requireNonNull(supervision, "supervision must not be null");
    }

    public ConversationPage handle(SearchConversationsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.page() < 0 || query.size() < 1 || query.size() > MAX_SIZE) {
            throw new InvalidRequestException("page must be 0 or more, size 1 to %d".formatted(MAX_SIZE));
        }
        if (query.from() != null && query.to() != null && query.to().isBefore(query.from())) {
            throw new InvalidRequestException("to must not be before from");
        }
        String text = query.query() == null || query.query().isBlank() ? null : query.query().strip();
        if (text != null && text.length() > MAX_QUERY_LENGTH) {
            throw new InvalidRequestException("q must be at most %d characters".formatted(MAX_QUERY_LENGTH));
        }
        // Skill codes are upper case (ACCOUNTS, CARDS, ...): "fraud" means FRAUD, not "no match".
        String skill = query.skill() == null || query.skill().isBlank()
                ? null
                : query.skill().strip().toUpperCase(java.util.Locale.ROOT);
        return supervision.search(new ConversationSearch(query.statuses(), skill, query.customerId(),
                query.advisorId(), text, query.from(), query.to()), query.page(), query.size());
    }
}
