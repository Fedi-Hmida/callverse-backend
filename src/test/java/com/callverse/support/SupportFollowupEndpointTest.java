package com.callverse.support;

import static com.callverse.auth.AuthenticatedRequests.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class SupportFollowupEndpointTest extends AbstractPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    UUID customerId, advisorId, conversationId, accountId, ticketId;
    RequestPostProcessor customer,advisor,supervisor,other;

    @BeforeEach void fixtures() {
        UUID customerUser=UUID.randomUUID(),advisorUser=UUID.randomUUID();
        customer=bearer(validToken(customerUser,"customer@support.test",UserRole.CUSTOMER));
        advisor=bearer(validToken(advisorUser,"advisor@support.test",UserRole.ADVISOR));
        supervisor=bearer(validToken(UUID.randomUUID(),"supervisor@support.test",UserRole.SUPERVISOR));
        other=bearer(validToken(UUID.randomUUID(),"other@support.test",UserRole.CUSTOMER));
        customerId=UUID.randomUUID(); advisorId=UUID.randomUUID(); conversationId=UUID.randomUUID(); accountId=UUID.randomUUID(); ticketId=UUID.randomUUID();
        jdbc.update("insert into customer(id,user_id,external_ref,first_name,last_name,region) values(?,?,?,'Support','Customer','Paris')",
                customerId,customerUser,"SUP-"+customerId.toString().substring(0,8));
        jdbc.update("insert into advisor(id,user_id,display_name,status,max_concurrent,credit_limit) values(?,?,'Support Advisor','AVAILABLE',2,15)",advisorId,advisorUser);
        UUID product=jdbc.queryForObject("select id from banking_product where category='CURRENT_ACCOUNT' limit 1",UUID.class);
        jdbc.update("insert into account(id,customer_id,product_id,iban,balance,status,opened_at) values(?,?,?,?,100,'ACTIVE',current_date)",
                accountId,customerId,product,"FR76"+accountId.toString().replace("-", "").substring(0,26));
        jdbc.update("insert into conversation(id,customer_id,advisor_id,skill_id,status) values(?,?,?,(select id from skill where code='ACCOUNTS'),'ACTIVE')",
                conversationId,customerId,advisorId);
        jdbc.update("insert into ticket(id,customer_id,conversation_id,title,category,status,severity) values(?,?,?,'Support test','OTHER','OPEN',2)",
                ticketId,customerId,conversationId);
    }
    private JsonNode call(RequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isBlank()?json.nullNode():json.readTree(response.getContentAsString());
    }
    private String payload(UUID requestId,String amount) throws Exception {
        return json.writeValueAsString(Map.of("customerId",customerId,"conversationId",conversationId,"accountId",accountId,
                "amount",new BigDecimal(amount),"reason","Support test goodwill","requestId",requestId));
    }
    private JsonNode state(String expected,String target,RequestPostProcessor actor,int status) throws Exception {
        return call(put("/api/v1/tickets/"+ticketId+"/status").with(actor).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedStatus",expected,"status",target))),status);
    }
    @Test void customerSeesOnlyOwnTicketsAndCommentsFollowStateMachine() throws Exception {
        assertThat(call(get("/api/v1/tickets?customerId="+customerId).with(customer),200).path("content").size()).isEqualTo(1);
        assertThat(call(get("/api/v1/tickets?customerId="+customerId).with(other),200).path("content").size()).isZero();
        call(get("/api/v1/tickets/"+ticketId).with(other),404);
        call(post("/api/v1/tickets/"+ticketId+"/comments").with(other).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"forbidden\"}"),404);
        call(post("/api/v1/tickets/"+ticketId+"/comments").with(customer).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Here is the requested detail.\"}"),201);
        assertThat(call(get("/api/v1/tickets/"+ticketId).with(advisor),200).path("comments").size()).isEqualTo(1);
        state("OPEN","RESOLVED",customer,409);
        state("OPEN","IN_PROGRESS",advisor,200);
        state("OPEN","RESOLVED",advisor,409);
        var resolved=state("IN_PROGRESS","RESOLVED",advisor,200);
        state("RESOLVED","CLOSED",advisor,409);
        var closed=state("RESOLVED","CLOSED",customer,200);
        assertThat(closed.path("resolvedAt")).isEqualTo(resolved.path("resolvedAt"));
        call(post("/api/v1/tickets/"+ticketId+"/comments").with(customer).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"too late\"}"),409);
        call(get("/api/v1/tickets"),401);
    }
    @Test void creditBooksExactlyOneRefundAndOneBalanceChangeAcrossRetries() throws Exception {
        String body=payload(UUID.randomUUID(),"10.00");
        var first=call(post("/api/v1/credits").with(advisor).contentType(MediaType.APPLICATION_JSON).content(body),201);
        var repeated=call(post("/api/v1/credits").with(advisor).contentType(MediaType.APPLICATION_JSON).content(body),201);
        assertThat(repeated.path("id")).isEqualTo(first.path("id"));
        assertThat(jdbc.queryForObject("select balance from account where id=?",BigDecimal.class,accountId)).isEqualByComparingTo("110.00");
        assertThat(jdbc.queryForObject("select count(*) from bank_transaction where account_id=?",Long.class,accountId)).isEqualTo(1);
        assertThat(call(get("/api/v1/credits?customerId="+customerId).with(customer),200).size()).isEqualTo(1);
        assertThat(call(get("/api/v1/credits?customerId="+customerId).with(other),200).size()).isZero();
        call(post("/api/v1/credits").with(customer).contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),"1.00")),403);
        call(post("/api/v1/credits").with(bearer(validToken(UUID.randomUUID(),"other-advisor@test",UserRole.ADVISOR)))
                .contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),"1.00")),403);
    }
    @Test void cumulativeRollingCeilingCannotBeBypassedAndSupervisionApprovalIsAudited() throws Exception {
        call(post("/internal/credits").with(serviceKey()).contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),"10.00")),200);
        var refused=call(post("/internal/credits").with(serviceKey()).contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),"6.00")),409);
        assertThat(refused.path("code").asText()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
        var approved=call(post("/api/v1/credits").with(supervisor).contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),"6.00")),201);
        assertThat(approved.path("approvedBy").isNull()).isFalse();
        assertThat(jdbc.queryForObject("select balance from account where id=?",BigDecimal.class,accountId)).isEqualByComparingTo("116.00");
    }
    @Test void creditValidationAndSimulatedOrFrozenAccountCannotMutateBalances() throws Exception {
        call(post("/internal/credits").with(serviceKey()).contentType(MediaType.APPLICATION_JSON).content("{}"),400);
        call(post("/api/v1/credits").with(advisor).contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),"1.001")),400);
        jdbc.update("update account set status='FROZEN' where id=?",accountId);
        call(post("/api/v1/credits").with(advisor).contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),"1.00")),400);
        jdbc.update("update account set status='ACTIVE' where id=?",accountId);
        jdbc.update("update conversation set run_id=? where id=?",UUID.randomUUID(),conversationId);
        call(post("/internal/credits").with(serviceKey()).contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),"1.00")),404);
        assertThat(jdbc.queryForObject("select balance from account where id=?",BigDecimal.class,accountId)).isEqualByComparingTo("100.00");
    }
    @Test void simultaneousDifferentRequestsCannotBothExceedCustomerCeiling() throws Exception {
        String first=payload(UUID.randomUUID(),"10.00"),second=payload(UUID.randomUUID(),"10.00");
        var a=CompletableFuture.supplyAsync(()->creditStatus(first));
        var b=CompletableFuture.supplyAsync(()->creditStatus(second));
        assertThat(java.util.List.of(a.join(),b.join())).containsExactlyInAnyOrder(201,409);
        assertThat(jdbc.queryForObject("select balance from account where id=?",BigDecimal.class,accountId)).isEqualByComparingTo("110.00");
    }
    private int creditStatus(String body) {
        try { return mvc.perform(post("/api/v1/credits").with(advisor).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus(); }
        catch(Exception exception) { throw new RuntimeException(exception); }
    }
}
