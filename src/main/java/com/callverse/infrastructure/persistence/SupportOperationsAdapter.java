package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.SupportOperations;
import com.callverse.core.domain.enums.TicketStatus;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.exceptions.CreditLimitExceededException;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class SupportOperationsAdapter implements SupportOperations {
    private final NamedParameterJdbcTemplate jdbc;
    private static final int PAGE_SIZE = 20;
    private static final String TICKET_SELECT = """
        select t.*, cu.first_name || ' ' || cu.last_name customer_name from ticket t
        join customer cu on cu.id=t.customer_id
        """;

    @Override @Transactional(readOnly=true)
    public TicketPage tickets(AuthenticatedPrincipal caller, UUID customerId, TicketStatus status, int page) {
        if (page < 0 || page > 10000) throw new InvalidRequestException("Invalid ticket page.");
        var params = new MapSqlParameterSource("offset", page * PAGE_SIZE).addValue("caller", caller.userId());
        String where = " where true";
        if (caller.role() == UserRole.CUSTOMER) where += " and cu.user_id=:caller and cu.is_simulated=false";
        if (customerId != null) { where += " and t.customer_id=:customer"; params.addValue("customer", customerId); }
        if (status != null) { where += " and t.status=:status"; params.addValue("status", status.name()); }
        var rows = jdbc.query(TICKET_SELECT + where + " order by t.created_at desc,t.id desc limit 21 offset :offset",
                params, ticketMapper(List.of()));
        return new TicketPage(rows.stream().limit(PAGE_SIZE).toList(), page, PAGE_SIZE, rows.size()>PAGE_SIZE);
    }

    @Override @Transactional(readOnly=true)
    public TicketView ticket(AuthenticatedPrincipal caller, UUID id) {
        requireTicket(caller, id, false);
        return readTicket(id);
    }

    @Override @Transactional
    public TicketView transition(AuthenticatedPrincipal caller, UUID id, TicketStatus expected, TicketStatus target) {
        TicketStatus current = requireTicket(caller, id, true);
        if (expected == null || target == null) throw new InvalidRequestException("Both expected and target status are required.");
        if (current == target) return readTicket(id);
        if (current != expected) throw new InvalidStateTransitionException("ticket", current, target);
        boolean customer = caller.role() == UserRole.CUSTOMER;
        boolean valid = customer ? current == TicketStatus.RESOLVED && target == TicketStatus.CLOSED
                : (current == TicketStatus.OPEN && (target == TicketStatus.IN_PROGRESS || target == TicketStatus.RESOLVED))
                || (current == TicketStatus.IN_PROGRESS && target == TicketStatus.RESOLVED)
                || (current == TicketStatus.RESOLVED && target == TicketStatus.IN_PROGRESS);
        if (!valid) throw new InvalidStateTransitionException("ticket", current, target);
        jdbc.update("update ticket set status=:status, resolved_at=case when :status='RESOLVED' then now() when :status='IN_PROGRESS' then null else resolved_at end where id=:id",
                Map.of("status",target.name(),"id",id));
        // Closing preserves the time at which the advisor resolved the work.
        if (target == TicketStatus.CLOSED) jdbc.update("update ticket set resolved_at=coalesce(resolved_at,now()) where id=:id",Map.of("id",id));
        return readTicket(id);
    }

    @Override @Transactional
    public Comment comment(AuthenticatedPrincipal caller, UUID id, String content) {
        TicketStatus status = requireTicket(caller,id,true);
        if (status == TicketStatus.CLOSED) throw new InvalidStateTransitionException("ticket", status, TicketStatus.IN_PROGRESS);
        if (content == null || content.isBlank() || content.length()>4000) throw new InvalidRequestException("Comment must contain 1 to 4000 characters.");
        UUID commentId = UUID.randomUUID();
        jdbc.update("insert into ticket_comment(id,ticket_id,author_id,author_role,content) values(:id,:ticket,:author,:role,:content)",
                Map.of("id",commentId,"ticket",id,"author",caller.userId(),"role",caller.role().name(),"content",content.trim()));
        return comments(id).stream().filter(c->c.id().equals(commentId)).findFirst().orElseThrow();
    }

    private TicketStatus requireTicket(AuthenticatedPrincipal caller, UUID id, boolean lock) {
        String ownership = caller.role() == UserRole.CUSTOMER ? " and cu.user_id=:caller and cu.is_simulated=false" : "";
        var statuses = jdbc.query("select t.status from ticket t join customer cu on cu.id=t.customer_id where t.id=:id" + ownership
                        + (lock ? " for update of t" : ""), Map.of("id",id,"caller",caller.userId()),
                (rs,n)->TicketStatus.valueOf(rs.getString(1)));
        if (statuses.isEmpty()) throw new ResourceNotFoundException("Ticket",id);
        return statuses.getFirst();
    }

    private TicketView readTicket(UUID id) {
        return jdbc.query(TICKET_SELECT+" where t.id=:id",Map.of("id",id),ticketMapper(comments(id))).getFirst();
    }
    private List<Comment> comments(UUID id) {
        return jdbc.query("""
            select tc.*,coalesce(u.first_name || ' ' || u.last_name,'Ancien utilisateur') author_name
            from ticket_comment tc left join app_user u on u.id=tc.author_id
            where tc.ticket_id=:id order by tc.created_at,tc.id
            """,Map.of("id",id),(rs,n)->new Comment(uuid(rs,"id"),uuid(rs,"ticket_id"),uuid(rs,"author_id"),
                rs.getString("author_role"),rs.getString("author_name"),rs.getString("content"),rs.getTimestamp("created_at").toInstant()));
    }
    private static RowMapper<TicketView> ticketMapper(List<Comment> comments) {
        return (rs,n)->new TicketView(uuid(rs,"id"),uuid(rs,"customer_id"),uuid(rs,"conversation_id"),rs.getString("customer_name"),
                rs.getString("title"),rs.getString("description"),rs.getString("category"),rs.getInt("severity"),
                TicketStatus.valueOf(rs.getString("status")),rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at")==null?null:rs.getTimestamp("resolved_at").toInstant(),comments);
    }

    @Override @Transactional(readOnly=true)
    public List<Credit> credits(AuthenticatedPrincipal caller, UUID customerId) {
        String where = caller.role()==UserRole.CUSTOMER ? " and cu.user_id=:caller and cu.is_simulated=false" : "";
        var params=new MapSqlParameterSource("caller",caller.userId());
        if(customerId!=null) { where+=" and cc.customer_id=:customer"; params.addValue("customer",customerId); }
        return jdbc.query("select cc.* from commercial_credit cc join customer cu on cu.id=cc.customer_id where true"+where
                +" order by cc.created_at desc,cc.id desc limit 100",params,creditMapper());
    }

    @Override @Transactional
    public Credit grant(AuthenticatedPrincipal caller, CreditRequest request) {
        if(caller.role()==UserRole.CUSTOMER) throw new ActionNotPermittedException("Customers cannot grant credit.");
        return apply(caller,request);
    }
    @Override @Transactional
    public Credit grantFromService(CreditRequest request) { return apply(null,request); }

    private Credit apply(AuthenticatedPrincipal caller, CreditRequest request) {
        if(request.customerId()==null || request.conversationId()==null || request.requestId()==null
                || request.amount()==null || request.reason()==null || request.reason().isBlank() || request.reason().length()>255)
            throw new InvalidRequestException("Customer, conversation, amount, reason and requestId are required.");
        BigDecimal amount;
        try { amount=request.amount().setScale(2,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException invalid) { throw new InvalidRequestException("Credit amount must have at most two decimal places."); }
        if(amount.signum()<=0 || amount.compareTo(new BigDecimal("999999.99"))>0) throw new InvalidRequestException("Credit amount is outside supported bounds.");
        // Serialize all grants for this customer, so splitting a request cannot defeat the rolling ceiling.
        var customer=jdbc.query("select id from customer where id=:id and is_simulated=false for no key update",Map.of("id",request.customerId()),(rs,n)->uuid(rs,"id"));
        if(customer.isEmpty()) throw new ResourceNotFoundException("Customer",request.customerId());
        var conversations=jdbc.queryForList("""
            select c.*, a.user_id advisor_user_id,a.credit_limit,a.is_simulated advisor_simulated
            from conversation c left join advisor a on a.id=c.advisor_id where c.id=:id and c.run_id is null
            """,Map.of("id",request.conversationId()));
        if(conversations.isEmpty() || !request.customerId().equals(conversations.getFirst().get("customer_id")))
            throw new ResourceNotFoundException("Conversation",request.conversationId());
        var conversation=conversations.getFirst();
        UUID advisorId=(UUID)conversation.get("advisor_id");
        if(advisorId==null || Boolean.TRUE.equals(conversation.get("advisor_simulated"))) throw new InvalidRequestException("A live assigned advisor is required for a credit.");
        boolean approval=caller!=null && (caller.role()==UserRole.SUPERVISOR || caller.role()==UserRole.ADMIN);
        if(caller!=null && !approval && !caller.userId().equals(conversation.get("advisor_user_id")))
            throw new ActionNotPermittedException("Only the assigned advisor or supervision may grant this credit.");
        var params=new MapSqlParameterSource("conversation",request.conversationId()).addValue("request",request.requestId());
        var existing=jdbc.query("select * from commercial_credit where conversation_id=:conversation and request_id=:request",params,creditMapper());
        if(!existing.isEmpty()) {
            Credit credit=existing.getFirst();
            if(credit.amount().compareTo(amount)!=0 || !credit.reason().equals(request.reason().trim())
                    || (request.accountId()!=null && !request.accountId().equals(credit.accountId())))
                throw new InvalidRequestException("requestId already belongs to a different credit request.");
            return credit;
        }
        // Follow the conversation writer's lock order: advisor before conversation.
        jdbc.queryForList("select id from advisor where id=:id for update",Map.of("id",advisorId));
        String status=jdbc.queryForObject("select status from conversation where id=:id for update",Map.of("id",request.conversationId()),String.class);
        if(!"ACTIVE".equals(status) && !(approval && "ESCALATED".equals(status)))
            throw new InvalidRequestException("Credit requires an active conversation (or an escalated case approved by supervision).");
        BigDecimal limit=jdbc.queryForObject("select credit_limit from advisor where id=:id",Map.of("id",advisorId),BigDecimal.class);
        BigDecimal total=jdbc.queryForObject("select coalesce(sum(amount),0) from commercial_credit where customer_id=:id and created_at>=now()-interval '24 hours'",
                Map.of("id",request.customerId()),BigDecimal.class);
        if(!approval && total.add(amount).compareTo(limit)>0) throw new CreditLimitExceededException(total.add(amount),limit);
        var accountParams=new MapSqlParameterSource("customer",request.customerId());
        String accountFilter="";
        if(request.accountId()!=null) { accountFilter=" and a.id=:account"; accountParams.addValue("account",request.accountId()); }
        var accounts=jdbc.queryForList("""
            select a.id from account a join banking_product p on p.id=a.product_id
            where a.customer_id=:customer and a.status='ACTIVE' and a.currency='EUR' and p.category='CURRENT_ACCOUNT'
            """+accountFilter+" order by a.opened_at,a.id for update of a",accountParams);
        if(accounts.isEmpty()) throw new InvalidRequestException("An active EUR current account is required.");
        if(request.accountId()==null && accounts.size()!=1) throw new InvalidRequestException("Select an account explicitly when several current accounts exist.");
        UUID accountId=(UUID)accounts.getFirst().get("id"), creditId=UUID.randomUUID(), transactionId=UUID.randomUUID();
        params.addValue("id",creditId).addValue("customer",request.customerId()).addValue("account",accountId)
                .addValue("transaction",transactionId).addValue("amount",amount).addValue("reason",request.reason().trim())
                .addValue("advisor",advisorId).addValue("approver",approval?caller.userId():null);
        jdbc.update("update account set balance=balance+:amount where id=:account",params);
        jdbc.update("""
            insert into bank_transaction(id,account_id,type,amount,currency,label,status)
            values(:transaction,:account,'REFUND',:amount,'EUR','Geste commercial CallVerse','BOOKED')
            """,params);
        jdbc.update("""
            insert into commercial_credit(id,customer_id,conversation_id,account_id,transaction_id,amount,reason,granted_by,approved_by,request_id)
            values(:id,:customer,:conversation,:account,:transaction,:amount,:reason,:advisor,:approver,:request)
            """,params);
        return jdbc.query("select * from commercial_credit where id=:id",params,creditMapper()).getFirst();
    }
    private static RowMapper<Credit> creditMapper() {
        return (rs,n)->new Credit(uuid(rs,"id"),uuid(rs,"customer_id"),uuid(rs,"conversation_id"),uuid(rs,"account_id"),
                uuid(rs,"transaction_id"),rs.getBigDecimal("amount"),rs.getString("reason"),uuid(rs,"granted_by"),uuid(rs,"approved_by"),
                uuid(rs,"request_id"),rs.getTimestamp("created_at").toInstant());
    }
    private static UUID uuid(ResultSet rs,String column) throws SQLException { return rs.getObject(column,UUID.class); }
}
