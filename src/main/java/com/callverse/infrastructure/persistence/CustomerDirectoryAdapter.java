package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.CustomerDirectory;
import com.callverse.core.application.exceptions.InvalidRequestException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CustomerDirectoryAdapter implements CustomerDirectory {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Page search(String query, int page) {
        if (query == null || query.length() > 120 || page < 0 || page > 10000) {
            throw new InvalidRequestException("Invalid customer directory filters.");
        }
        var items = jdbc.query("""
            select id, first_name, last_name, region from customer
            where is_simulated = false and (:query = '' or position(lower(:query) in
                lower(first_name || ' ' || last_name || ' ' || coalesce(region, '') || ' ' || external_ref)) > 0)
            order by lower(last_name), lower(first_name), id limit 21 offset :offset
            """, Map.of("query", query.trim(), "offset", page * 20),
            (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("first_name"),
                    rs.getString("last_name"), rs.getString("region")));
        return new Page(items.stream().limit(20).toList(), page, 20, items.size() > 20);
    }
}
