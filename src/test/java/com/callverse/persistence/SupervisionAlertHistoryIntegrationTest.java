package com.callverse.persistence;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.core.domain.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class SupervisionAlertHistoryIntegrationTest extends AbstractPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private RequestPostProcessor as(UserRole role) {
        var id=UUID.randomUUID();return bearer(validToken(id,id+"@alert-history.test",role));
    }
    private JsonNode list(String query,RequestPostProcessor caller,int expected) throws Exception {
        var response=mvc.perform(get("/api/v1/supervision/alerts"+query).with(caller)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return json.readTree(response.getContentAsString());
    }
    private UUID customer(boolean simulated) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into customer(id,external_ref,first_name,last_name,region,is_simulated) values(?,?,?,?,?,?)",id,
                "HISTORY-"+id.toString().substring(0,8),"Alert","Customer","Paris",simulated);
        return id;
    }
    private UUID escalation(UUID customer,UUID run) {
        UUID contact=UUID.randomUUID();UUID event=UUID.randomUUID();
        jdbc.update("insert into conversation(id,customer_id,status,run_id) values(?,?,'RESOLVED',?)",contact,customer,run);
        jdbc.update("insert into escalation(id,conversation_id,reason,raised_by,status,resolved_at) values(?,?,'Persisted event','ADVISOR','RESOLVED',now())",event,contact);
        return event;
    }
    @Test void persistedResolvedEscalationsAndFraudBlocksSurviveNewSessionsAndPaging() throws Exception {
        UUID customer=customer(false);UUID escalation=escalation(customer,null);
        UUID account=UUID.randomUUID();UUID card=UUID.randomUUID();
        UUID product=jdbc.queryForObject("select id from banking_product limit 1",UUID.class);
        jdbc.update("insert into account(id,customer_id,product_id,iban,status,opened_at) values(?,?,?,?,'ACTIVE',current_date)",account,
                customer,product,"FR"+UUID.randomUUID().toString().replace("-", ""));
        jdbc.update("insert into card(id,account_id,pan_last4,network,type,status,expires_on,daily_limit,blocked_at,block_reason) "
                +"values(?,?,'4242','VISA','DEBIT','BLOCKED',current_date+365,100,now(),'FRAUD_SUSPECTED')",card,account);
        UUID synthetic=escalation(customer(true),UUID.randomUUID());
        var first=list("?size=100",as(UserRole.SUPERVISOR),200);
        assertThat(first.path("content").toString()).contains(escalation.toString(),card.toString()).doesNotContain(synthetic.toString());
        assertThat(first.path("content").toString()).doesNotContain("Persisted event","iban","balance");
        assertThat(first.path("page").path("totalElements").asLong()).isGreaterThanOrEqualTo(2);
        var newSession=list("?size=100",as(UserRole.ADMIN),200);
        assertThat(newSession.path("content")).isEqualTo(first.path("content"));
        var pageOne=list("?size=1",as(UserRole.SUPERVISOR),200);
        var pageTwo=list("?size=1&page=1",as(UserRole.SUPERVISOR),200);
        assertThat(pageOne.path("content").get(0)).isNotEqualTo(pageTwo.path("content").get(0));
        assertThat(pageTwo.path("page").path("number").asInt()).isEqualTo(1);
    }
    @Test void historyRequiresSupervisionAndValidPageBounds() throws Exception {
        assertThat(mvc.perform(get("/api/v1/supervision/alerts")).andReturn().getResponse().getStatus()).isEqualTo(401);
        list("",as(UserRole.ADVISOR),403);list("",as(UserRole.CUSTOMER),403);
        list("?page=-1",as(UserRole.SUPERVISOR),400);list("?size=101",as(UserRole.SUPERVISOR),400);
        list("?size=0",as(UserRole.SUPERVISOR),400);
    }
}
