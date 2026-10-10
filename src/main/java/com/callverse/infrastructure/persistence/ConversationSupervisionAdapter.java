package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.ConversationSupervision;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.Intent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link ConversationSupervision} with one paged SQL query and one count.
 *
 * <p>SQL rather than JPA because each row joins the customer, the advisor and the skill and adds
 * three per-conversation figures (message count, last message time, pending escalation): as entities
 * that would be the N+1 this avoids. The per-row figures are correlated sub-queries bounded by the
 * page size, served by {@code idx_message_conv (conversation_id, sent_at)}.
 *
 * <p><strong>The filters are fixed SQL fragments, never user text:</strong> only the values travel,
 * as bound parameters. The text filter's LIKE wildcards are escaped, so a {@code %} typed by the
 * supervisor matches a {@code %}.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ConversationSupervisionAdapter implements ConversationSupervision {

    private static final String FROM = """
              from conversation c
              join customer cu on cu.id = c.customer_id
              left join advisor a on a.id = c.advisor_id
              left join skill s on s.id = c.skill_id
             where c.run_id is null
            """;

    private static final String SELECT = """
            select c.id, c.status, s.code as skill, c.intent, c.channel,
                   c.queued_at, c.assigned_at, c.ended_at,
                   cu.id as customer_id, cu.first_name || ' ' || cu.last_name as customer_name,
                   cu.external_ref as customer_reference,
                   a.id as advisor_id, a.display_name as advisor_name,
                   c.wait_seconds, c.handle_seconds, c.sla_met,
                   (select count(*) from message m where m.conversation_id = c.id) as message_count,
                   (select max(m.sent_at) from message m where m.conversation_id = c.id) as last_message_at,
                   exists (select 1 from escalation e
                            where e.conversation_id = c.id and e.status = 'PENDING') as pending_escalation
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public ConversationPage search(ConversationSearch search, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(search, params);

        Long total = jdbc.queryForObject("select count(*) " + FROM + where, params, Long.class);
        long totalElements = total == null ? 0 : total;

        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<ConversationSummary> content = jdbc.query(
                SELECT + FROM + where + " order by c.queued_at desc, c.id limit :limit offset :offset",
                params, (rs, row) -> toSummary(rs));

        int totalPages = (int) ((totalElements + size - 1) / size);
        return new ConversationPage(content, page, size, totalElements, totalPages);
    }

    private static String where(ConversationSearch search, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        if (!search.statuses().isEmpty()) {
            where.append(" and c.status in (:statuses)");
            params.addValue("statuses", search.statuses().stream().map(Enum::name).toList());
        }
        if (search.skill() != null) {
            where.append(" and s.code = :skill");
            params.addValue("skill", search.skill());
        }
        if (search.customerId() != null) {
            where.append(" and c.customer_id = :customerId");
            params.addValue("customerId", search.customerId());
        }
        if (search.advisorId() != null) {
            where.append(" and c.advisor_id = :advisorId");
            params.addValue("advisorId", search.advisorId());
        }
        if (search.query() != null && !search.query().isBlank()) {
            // A plain string, not a text block: a text block strips the leading space this fragment needs.
            where.append(" and (lower(cu.first_name || ' ' || cu.last_name) like :pattern escape '!'"
                    + " or lower(cu.external_ref) like :pattern escape '!')");
            params.addValue("pattern", pattern(search.query()));
        }
        if (search.from() != null) {
            where.append(" and c.queued_at >= :from");
            params.addValue("from", utc(search.from()));
        }
        if (search.to() != null) {
            where.append(" and c.queued_at < :to");
            params.addValue("to", utc(search.to()));
        }
        return where.toString();
    }

    private static String pattern(String query) {
        String escaped = query.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static ConversationSummary toSummary(ResultSet rs) throws SQLException {
        String intent = rs.getString("intent");
        return new ConversationSummary(
                rs.getObject("id", UUID.class),
                ConversationStatus.valueOf(rs.getString("status")),
                rs.getString("skill"),
                intent == null ? null : Intent.valueOf(intent),
                rs.getString("channel"),
                instant(rs, "queued_at"),
                instant(rs, "assigned_at"),
                instant(rs, "ended_at"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getString("customer_reference"),
                rs.getObject("advisor_id", UUID.class),
                rs.getString("advisor_name"),
                rs.getObject("wait_seconds", Integer.class),
                rs.getObject("handle_seconds", Integer.class),
                rs.getObject("sla_met", Boolean.class),
                rs.getLong("message_count"),
                instant(rs, "last_message_at"),
                rs.getBoolean("pending_escalation"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
