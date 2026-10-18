package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.WorkforceOperations;
import com.callverse.core.application.interfaces.WorkforceOperations.WorkforceInputs;
import com.callverse.core.domain.enums.SkillCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads current live queue pressure and spare advisor capacity, excluding simulation data. */
@Component
@Transactional(readOnly = true)
class WorkforceOperationsAdapter implements WorkforceOperations {

    private static final List<String> SKILL_CODES = java.util.Arrays.stream(SkillCode.values())
            .map(Enum::name)
            .toList();

    private static final String QUEUES = """
            select s.code,
                   count(c.id) as waiting,
                   coalesce(avg(extract(epoch from (:now - c.queued_at))), 0) as average_wait_seconds
              from skill s
              left join conversation c
                on c.skill_id = s.id
               and c.status = 'QUEUED'
               and c.run_id is null
             where s.code in (:skills)
             group by s.code
            """;

    private static final String AVAILABLE = """
            select s.code,
                   count(distinct a.id) filter (
                       where a.status in ('AVAILABLE', 'BUSY')
                         and a.is_simulated = false
                         and a.max_concurrent > (
                             select count(*)
                               from conversation held
                              where held.advisor_id = a.id
                                and held.status in ('ASSIGNED', 'ACTIVE', 'ESCALATED')
                                and held.run_id is null
                         )
                   ) as available
              from skill s
              left join advisor_skill advisor_skill on advisor_skill.skill_id = s.id
              left join advisor a on a.id = advisor_skill.advisor_id
             where s.code in (:skills)
             group by s.code
            """;

    private final NamedParameterJdbcTemplate jdbc;

    WorkforceOperationsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WorkforceInputs inputs(Instant now) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("skills", SKILL_CODES)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        EnumMap<SkillCode, Long> queues = new EnumMap<>(SkillCode.class);
        EnumMap<SkillCode, Double> averageWait = new EnumMap<>(SkillCode.class);
        jdbc.query(QUEUES, parameters, (rs, row) -> {
            SkillCode skill = SkillCode.valueOf(rs.getString("code"));
            queues.put(skill, rs.getLong("waiting"));
            averageWait.put(skill, rs.getDouble("average_wait_seconds"));
            return null;
        });

        EnumMap<SkillCode, Integer> available = new EnumMap<>(SkillCode.class);
        jdbc.query(AVAILABLE, parameters, (rs, row) -> {
            available.put(SkillCode.valueOf(rs.getString("code")), Math.toIntExact(rs.getLong("available")));
            return null;
        });
        requireComplete(queues, averageWait, available);
        return new WorkforceInputs(queues, averageWait, available);
    }

    @SafeVarargs
    private static void requireComplete(Map<SkillCode, ?>... values) {
        EnumSet<SkillCode> expected = EnumSet.allOf(SkillCode.class);
        for (Map<SkillCode, ?> value : values) {
            if (!value.keySet().equals(expected)) {
                throw new IllegalStateException("Workforce reference skills are missing from the database.");
            }
        }
    }
}
