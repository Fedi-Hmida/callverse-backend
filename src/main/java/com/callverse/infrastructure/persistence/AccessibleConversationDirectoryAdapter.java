package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.AccessibleConversationDirectory;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.domain.enums.ConversationStatus;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AccessibleConversationDirectoryAdapter implements AccessibleConversationDirectory {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Page search(AuthenticatedPrincipal caller, String query, ConversationStatus status, int page) {
        if (page < 0 || page > 10000 || query.length() > 120) throw new InvalidRequestException("Invalid directory filters.");
        String ownership = switch (caller.role()) {
            case CUSTOMER -> "cu.user_id = :userId";
            case ADVISOR -> "a.user_id = :userId";
            case SUPERVISOR, ADMIN -> "true";
        };
        String sql = """
            select c.id, cu.first_name || ' ' || cu.last_name as customer_name,
                   a.display_name as advisor_name, s.code as skill, c.intent, c.status,
                   c.queued_at, left(coalesce(m.content, ''), 180) as preview
            from conversation c
            join customer cu on cu.id = c.customer_id
            join skill s on s.id = c.skill_id
            left join advisor a on a.id = c.advisor_id
            left join lateral (select content from message where conversation_id = c.id and sender in ('CUSTOMER', 'ADVISOR')
                               order by sent_at desc, id desc limit 1) m on true
            where c.run_id is null and (%s)
              and (:status = '' or c.status = :status)
              and (:query = '' or position(lower(:query) in lower(cu.first_name || ' ' || cu.last_name
                   || ' ' || coalesce(a.display_name, '') || ' ' || coalesce(c.intent, '')
                   || ' ' || s.code || ' ' || s.label || ' ' || coalesce(m.content, ''))) > 0)
            order by c.queued_at desc, c.id desc limit 31 offset :offset
            """.formatted(ownership);
        var items = jdbc.query(sql, Map.of("userId", caller.userId(), "query", query.trim(),
                "status", status == null ? "" : status.name(), "offset", page * 30),
                (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("customer_name"),
                        rs.getString("advisor_name"), rs.getString("skill"), rs.getString("intent"),
                        ConversationStatus.valueOf(rs.getString("status")), rs.getTimestamp("queued_at").toInstant(),
                        rs.getString("preview")));
        return new Page(items.stream().limit(30).toList(), page, 30, items.size() > 30);
    }
}
