package com.callverse.admin;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
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

/** Real account -> real profile -> explicitly authorized skill -> assigned conversation. */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class AdvisorOnboardingEndpointTest extends AbstractPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private RequestPostProcessor admin;

    @BeforeEach void admin() {
        admin = bearer(validToken(UUID.randomUUID(), "onboarding-admin@bank.test", UserRole.ADMIN));
    }

    private JsonNode call(RequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString().isEmpty() ? json.nullNode() : json.readTree(response.getContentAsString());
    }

    private JsonNode create(UserRole role) throws Exception {
        return call(post("/api/v1/admin/users").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", UUID.randomUUID()+"@bank.test", "firstName", "New",
                        "lastName", "Advisor", "role", role.name(), "password", "onboarding-test-2026"))), 201);
    }

    private String profile(String id) { return "/api/v1/admin/users/"+id+"/advisor-profile"; }
    private RequestBuilder configure(String id, String body, RequestPostProcessor caller) {
        return put(profile(id)).with(caller).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private String configuration(String skills) {
        return "{\"status\":\"AVAILABLE\",\"maxConcurrent\":2,\"skills\":"+skills+"}";
    }

    @Test void newAdvisorIsSafeAndCanServeOnlyAfterExplicitProvisioning() throws Exception {
        var user = create(UserRole.ADVISOR);
        String id = user.path("id").asText();
        var advisor = bearer(validToken(UUID.fromString(id), user.path("email").asText(), UserRole.ADVISOR));
        var initial = call(get(profile(id)).with(admin), 200);
        assertThat(initial.path("status").asText()).isEqualTo("OFFLINE");
        assertThat(initial.path("creditLimit").decimalValue()).isZero();
        assertThat(initial.path("maxConcurrent").asInt()).isEqualTo(1);
        assertThat(initial.path("skills").isEmpty()).isTrue();
        call(get("/api/v1/conversations/mine").with(advisor), 200);
        call(post("/api/v1/queues/ACCOUNTS/next").with(advisor), 403);

        var configured = call(configure(id, configuration("{\"ACCOUNTS\":2}"), admin), 200);
        assertThat(configured.path("id")).isEqualTo(initial.path("id"));
        assertThat(configured.path("skills").path("ACCOUNTS").asInt()).isEqualTo(2);
        assertThat(configured.path("creditLimit").decimalValue()).isZero();
        call(post("/api/v1/queues/FRAUD/next").with(advisor), 403);
        call(configure(id,"{\"status\":\"OFFLINE\",\"maxConcurrent\":2,\"skills\":{\"ACCOUNTS\":2}}",admin),200);
        call(post("/api/v1/queues/ACCOUNTS/next").with(advisor),409);
        call(configure(id,configuration("{\"ACCOUNTS\":2}"),admin),200);

        UUID customer = UUID.randomUUID();
        jdbc.update("insert into customer (id,external_ref,first_name,last_name,region) values (?,?,?,?,?)",
                customer, "ONBOARD-"+customer.toString().substring(0,8), "Audit", "Customer", "Paris");
        var contact = call(post("/api/v1/conversations").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("customerId",customer,"skill","ACCOUNTS"))),201);
        var taken = call(post("/api/v1/queues/ACCOUNTS/next").with(advisor),200);
        assertThat(taken.path("advisorId").asText()).isEqualTo(initial.path("id").asText());
        assertThat(taken.path("id")).isEqualTo(contact.path("id"));
        call(post("/api/v1/conversations/"+contact.path("id").asText()+"/abandon").with(admin),200);
        var emptied = call(configure(id, configuration("{}"),admin),200);
        assertThat(emptied.path("skills").isEmpty()).isTrue();
        call(post("/api/v1/queues/ACCOUNTS/next").with(advisor),403);
    }

    @Test void promotionAndRepeatedRoleUpdatesKeepExactlyOneProfile() throws Exception {
        String id = create(UserRole.CUSTOMER).path("id").asText();
        var role = put("/api/v1/admin/users/"+id+"/role").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADVISOR\"}");
        call(role,200);
        var first = call(get(profile(id)).with(admin),200);
        call(configure(id,configuration("{\"CARDS\":1}"),admin),200);
        call(role,200);
        var second = call(get(profile(id)).with(admin),200);
        assertThat(second.path("id")).isEqualTo(first.path("id"));
        assertThat(second.path("skills").path("CARDS").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from advisor where user_id=? and is_simulated=false",Long.class,
                UUID.fromString(id))).isEqualTo(1);
    }

    @Test void validationUnknownSkillsAndRolesCannotGrantCapabilities() throws Exception {
        String id = create(UserRole.ADVISOR).path("id").asText();
        call(configure(id,configuration("{\"ACCOUNTS\":4}"),admin),400);
        call(configure(id,"{\"status\":\"AVAILABLE\",\"maxConcurrent\":0,\"skills\":{}}",admin),400);
        call(configure(id,configuration("{\"UNKNOWN_SKILL\":1}"),admin),400);
        var nonAdmin = bearer(validToken(UUID.randomUUID(),"supervisor-onboard@bank.test",UserRole.SUPERVISOR));
        call(get(profile(id)).with(nonAdmin),403);
        call(configure(id,configuration("{\"ACCOUNTS\":1}"),nonAdmin),403);
        call(get(profile(id)),401);
        call(put(profile(id)).contentType(MediaType.APPLICATION_JSON).content(configuration("{}")),401);
        String customer = create(UserRole.CUSTOMER).path("id").asText();
        call(configure(customer,configuration("{\"ACCOUNTS\":1}"),admin),409);
        assertThat(call(get(profile(id)).with(admin),200).path("skills").isEmpty()).isTrue();
    }

    @Test void explicitConfigurationRepairsLegacyMissingProfileWithoutExtraPrivileges() throws Exception {
        String id = create(UserRole.ADVISOR).path("id").asText();
        jdbc.update("delete from advisor where user_id=?",UUID.fromString(id));
        var repaired=call(configure(id,configuration("{\"CREDIT\":1}"),admin),200);
        assertThat(repaired.path("creditLimit").decimalValue()).isZero();
        assertThat(repaired.path("skills").size()).isEqualTo(1);
    }

    @Test void customerCreationPromotionAndRepeatedRoleUpdatesLinkOneSafeContactProfile() throws Exception {
        String id=create(UserRole.CUSTOMER).path("id").asText();
        UUID userId=UUID.fromString(id);
        assertThat(jdbc.queryForObject("select count(*) from customer where user_id=? and is_simulated=false",Long.class,userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from account a join customer c on c.id=a.customer_id where c.user_id=?",Long.class,userId)).isZero();
        var user=create(UserRole.SUPERVISOR);
        String promoted=user.path("id").asText();
        var request=put("/api/v1/admin/users/"+promoted+"/role").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"CUSTOMER\"}");
        call(request,200);call(request,200);
        UUID promotedId=UUID.fromString(promoted);
        assertThat(jdbc.queryForObject("select count(*) from customer where user_id=? and is_simulated=false",Long.class,promotedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select segment from customer where user_id=?",String.class,promotedId)).isEqualTo("MASS");
        assertThat(jdbc.queryForObject("select tenure_months from customer where user_id=?",Integer.class,promotedId)).isZero();
    }
}
