package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.SupervisionAlert;
import com.callverse.core.application.interfaces.SupervisionAlertHistory;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Existing escalation/card facts survive sessions and restarts; no second, lossy event store is needed. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class SupervisionAlertHistoryAdapter implements SupervisionAlertHistory {
    private final JdbcTemplate jdbc;
    private static final String EVENTS = """
        select 'ESCALATION_RAISED' as type, e.id as event_id, e.created_at as occurred_at,
               c.customer_id, c.id as conversation_id, e.id as escalation_id,
               null::uuid as card_id, null::varchar as card_last4
          from escalation e join conversation c on c.id=e.conversation_id
          join customer customer on customer.id=c.customer_id
         where c.run_id is null and customer.is_simulated=false
        union all
        select 'CARD_BLOCKED_FRAUD', card.id, card.blocked_at, account.customer_id,
               null::uuid, null::uuid, card.id, card.pan_last4
          from card join account on account.id=card.account_id
          join customer on customer.id=account.customer_id
         where card.block_reason='FRAUD_SUSPECTED' and card.blocked_at is not null
           and customer.is_simulated=false
        """;

    @Override public AlertPage list(int page, int size) {
        long count = jdbc.queryForObject("select count(*) from ("+EVENTS+") events",Long.class);
        var alerts = jdbc.query("select * from ("+EVENTS+") events order by occurred_at desc,event_id desc limit ? offset ?",
                (rs,row) -> new SupervisionAlert(SupervisionAlert.SCHEMA_VERSION,
                        SupervisionAlert.Type.valueOf(rs.getString("type")), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("customer_id",UUID.class),rs.getObject("conversation_id",UUID.class),
                        rs.getObject("escalation_id",UUID.class),rs.getObject("card_id",UUID.class),rs.getString("card_last4")),
                size,(long)page*size);
        return new AlertPage(alerts,page,size,count,(int)Math.min(Integer.MAX_VALUE,(count+size-1)/size));
    }
}
