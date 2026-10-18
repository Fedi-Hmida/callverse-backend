package com.callverse.persistence;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.callverse.core.application.interfaces.*;
import com.callverse.core.domain.enums.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
class WorkforceApplicationIntegrationTest extends AbstractPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccounts accounts;
    @Autowired AdvisorAdministration profiles;
    @Autowired WorkforceDecisionLog decisions;
    @Autowired Clock clock;
    private UUID adminId;
    private RequestPostProcessor supervisor;
    private UUID supervisorId;

    @BeforeEach void setup() {
        adminId=jdbc.queryForObject("select id from app_user where email='admin@callverse.local'",UUID.class);
        supervisorId=UUID.randomUUID();
        supervisor=bearer(validToken(supervisorId,supervisorId+"@workforce.test",UserRole.SUPERVISOR));
    }
    private AdvisorAdministration.Profile donor() {
        var user=accounts.create(adminId,new UserAccounts.NewAccount(UUID.randomUUID()+"@workforce.test","Available","Advisor",UserRole.ADVISOR,"not-used-in-this-test"));
        return profiles.configure(adminId,user.id(),new AdvisorAdministration.Configuration(AdvisorStatus.AVAILABLE,1,Map.of("CARDS",1,"FRAUD",2)));
    }
    private long proposal(int count) {
        Map<SkillCode,Long> queues=new EnumMap<>(SkillCode.class);
        Map<SkillCode,Double> waits=new EnumMap<>(SkillCode.class);
        Map<SkillCode,Integer> available=new EnumMap<>(SkillCode.class);
        for(var skill:SkillCode.values()) { queues.put(skill,0L);waits.put(skill,0.0);available.put(skill,1); }
        return decisions.record(new WorkforceAgent.WorkforceState(queues,waits,available,1,true,10,1),
                new WorkforceAgent.WorkforceProposal(WorkforceActionType.REASSIGN,SkillCode.CARDS,SkillCode.ACCOUNTS,count,
                        "Transfer idle capacity",Map.of(),"THRESHOLD",1,true),clock.instant());
    }
    private String route(long id) { return "/api/v1/supervision/workforce/recommendations/"+id; }
    private RequestBuilder apply(long decision,List<UUID> ids,RequestPostProcessor who) throws Exception {
        return post(route(decision)+"/apply").with(who).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("advisorIds",ids,"targetSkillLevel",2)));
    }
    private JsonNode call(RequestBuilder request,int expected) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return json.readTree(response.getContentAsString());
    }
    @Test void humanSelectedTransferChangesRealSkillsAndRecordsApprovalOnlyOnce() throws Exception {
        var advisor=donor(); long decision=proposal(1);
        var options=call(get(route(decision)+"/candidates").with(supervisor),200);
        assertThat(options.path("candidates").toString()).contains(advisor.id().toString());
        var result=call(apply(decision,List.of(advisor.id()),supervisor),200);
        assertThat(result.path("alreadyApplied").asBoolean()).isFalse();
        assertThat(result.path("approvedBy").asText()).isEqualTo(supervisorId.toString());
        var changed=profiles.profile(adminId,advisor.userId());
        assertThat(changed.skills()).containsEntry("ACCOUNTS",2).containsEntry("FRAUD",2).doesNotContainKey("CARDS");
        assertThat(changed.creditLimit()).isZero();
        assertThat(jdbc.queryForObject("select approved_by from agent_decision where id=?",UUID.class,decision)).isEqualTo(supervisorId);
        assertThat(call(apply(decision,List.of(advisor.id()),supervisor),200).path("alreadyApplied").asBoolean()).isTrue();
        call(apply(decision,List.of(donor().id()),supervisor),409);
        assertThat(call(get(route(decision)+"/candidates").with(supervisor),200).path("applied").asBoolean()).isTrue();
    }
    @Test void freshnessFeasibilityAndDistinctSelectionsAreRecheckedBeforeAnyWrite() throws Exception {
        var first=donor();var second=donor();long stale=proposal(1);
        jdbc.update("update agent_decision set created_at=? where id=?",java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(16))),stale);
        call(apply(stale,List.of(first.id()),supervisor),409);
        long fresh=proposal(2);
        call(get(route(fresh)+"/candidates").with(supervisor),200);
        jdbc.update("update advisor set status='OFFLINE' where id=?",second.id());
        call(apply(fresh,List.of(first.id(),second.id()),supervisor),409);
        assertThat(profiles.profile(adminId,first.userId()).skills()).containsKey("CARDS").doesNotContainKey("ACCOUNTS");
        call(apply(fresh,List.of(first.id()),supervisor),409);
        call(apply(fresh,List.of(first.id(),first.id()),supervisor),400);
        assertThat(jdbc.queryForObject("select approved_by from agent_decision where id=?",UUID.class,fresh)).isNull();
    }
    @Test void permissionsAndActiveConversationExcludeUnsafeApplication() throws Exception {
        var advisor=donor();long decision=proposal(1);
        var noRole=bearer(validToken(UUID.randomUUID(),"advisor-role@workforce.test",UserRole.ADVISOR));
        call(get(route(decision)+"/candidates"),401);
        call(get(route(decision)+"/candidates").with(noRole),403);
        call(apply(decision,List.of(advisor.id()),noRole),403);
        UUID customer=UUID.randomUUID();
        jdbc.update("insert into customer(id,external_ref,first_name,last_name,region) values(?,?,?,?,?)",customer,
                "WORKFORCE-"+customer.toString().substring(0,8),"Test","Customer","Paris");
        UUID contact=UUID.randomUUID();
        jdbc.update("insert into conversation(id,customer_id,advisor_id,status) values(?,?,?,'ACTIVE')",contact,customer,advisor.id());
        call(apply(decision,List.of(advisor.id()),supervisor),409);
        assertThat(profiles.profile(adminId,advisor.userId()).skills()).containsKey("CARDS");
        jdbc.update("update conversation set status='ABANDONED' where id=?",contact);
    }
    @Test void simultaneousIdenticalApprovalsDoNotApplyTheTransferTwice() throws Exception {
        var advisor=donor();long decision=proposal(1);
        // Register the real supervisor account before concurrent HTTP requests begin.
        call(get(route(decision)+"/candidates").with(supervisor),200);
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> action=() -> { start.await();return call(apply(decision,List.of(advisor.id()),supervisor),200); };
            var one=pool.submit(action);var two=pool.submit(action);start.countDown();
            var results=List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
            assertThat(results.stream().filter(value -> value.path("alreadyApplied").asBoolean()).count()).isEqualTo(1);
        }
        assertThat(profiles.profile(adminId,advisor.userId()).skills()).containsEntry("ACCOUNTS",2).doesNotContainKey("CARDS");
    }
}
