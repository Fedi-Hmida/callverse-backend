package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import com.callverse.core.application.interfaces.LiveOperations;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@link LiveOperations} with two aggregate queries over {@code conversation}, live rows only.
 *
 * <p>Aggregated in SQL rather than loaded and counted in Java: one row comes back whatever the
 * volume. The figures read the columns written at each transition ({@code wait_seconds},
 * {@code sla_met}, {@code ended_at}) and never recompute them from timestamps.
 *
 * <p>The snapshot scans the day's live conversations. At demonstration volume that is nothing; an
 * index on {@code (run_id, ended_at)} is the request to raise if the live table ever grows large.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class LiveOperationsAdapter implements LiveOperations {

    private static final String QUEUE_DEPTHS = """
            select s.code, count(c.id) as waiting, min(c.queued_at) as oldest
              from skill s
              left join conversation c
                on c.skill_id = s.id
               and c.status = 'QUEUED'
               and c.run_id is null
             group by s.code
             order by s.code
            """;

    private static final String SNAPSHOT = """
            select count(*) filter (where status in ('ASSIGNED', 'ACTIVE', 'ESCALATED'))          as in_service,
                   count(*) filter (where status = 'RESOLVED' and ended_at >= :since)             as resolved,
                   count(*) filter (where status = 'ABANDONED' and ended_at >= :since)            as abandoned,
                   avg(wait_seconds) filter (where assigned_at >= :since)                         as average_wait,
                   avg(case when sla_met then 1.0 else 0.0 end)
                       filter (where coalesce(assigned_at, ended_at) >= :since
                                 and sla_met is not null)                                         as sla_ratio
              from conversation
             where run_id is null
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<QueueDepth> queueDepths(Instant now) {
        return jdbc.query(QUEUE_DEPTHS, (rs, row) -> {
            OffsetDateTime oldest = rs.getObject("oldest", OffsetDateTime.class);
            Integer oldestWait = oldest == null
                    ? null
                    : (int) Math.max(0, Duration.between(oldest.toInstant(), now).getSeconds());
            return new QueueDepth(rs.getString("code"), rs.getLong("waiting"), oldestWait);
        });
    }

    @Override
    public LiveKpiSnapshot snapshot(Instant since, Instant now) {
        List<QueueDepth> queues = queueDepths(now);
        long waitingTotal = queues.stream().mapToLong(QueueDepth::waiting).sum();
        MapSqlParameterSource params =
                new MapSqlParameterSource("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        return jdbc.queryForObject(SNAPSHOT, params, (rs, row) -> {
            long resolved = rs.getLong("resolved");
            long abandoned = rs.getLong("abandoned");
            long ended = resolved + abandoned;
            return new LiveKpiSnapshot(
                    LiveKpiSnapshot.SCHEMA_VERSION,
                    now,
                    since,
                    queues,
                    waitingTotal,
                    rs.getLong("in_service"),
                    resolved,
                    abandoned,
                    nullableDouble(rs, "average_wait"),
                    nullableDouble(rs, "sla_ratio"),
                    ended == 0 ? null : (double) abandoned / ended);
        });
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
