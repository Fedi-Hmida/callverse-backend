package com.callverse.admin;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Real database + real prod security, including the operational effects of configuration. */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class AdminReferenceEndpointTest extends AbstractPersistenceTest {
    private static final String BASE = "/api/v1/admin/";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationLifecycle conversations;
    private RequestPostProcessor admin;
    private final Map<String, List<UUID>> created = new HashMap<>();
    private final List<UUID> customerIds = new ArrayList<>();
    private final List<UUID> skillIds = new ArrayList<>();

    @BeforeEach void setup() { admin = bearer(validToken(UUID.randomUUID(), "reference@admin.test", UserRole.ADMIN)); }
    @AfterEach void cleanup() {
        for (UUID customer : customerIds) {
            jdbc.update("delete from conversation where customer_id=?", customer);
            jdbc.update("delete from account where customer_id=?", customer);
            jdbc.update("delete from customer where id=?", customer);
        }
        for (String table : List.of("routing_rule", "sla_policy", "kb_article", "banking_product"))
            for (UUID id : created.getOrDefault(table, List.of())) jdbc.update("delete from " + table + " where id=?", id);
        for (UUID id : skillIds) jdbc.update("delete from skill where id=?", id);
    }
    private JsonNode call(RequestBuilder request, int status) throws Exception {
        var result = mvc.perform(request).andReturn(); String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(status);
        return body.isBlank() ? json.nullNode() : json.readTree(body);
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value) throws Exception {
        return request.with(admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(value));
    }
    private JsonNode create(String path, String table, Object value) throws Exception {
        JsonNode result = call(body(post(BASE + path), value), 201);
        created.computeIfAbsent(table, unused -> new ArrayList<>()).add(UUID.fromString(result.path("id").asText())); return result;
    }
    private Map<String, Object> product(String code) {
        return new HashMap<>(Map.of("code", code, "name", "Offre test", "category", "SAVINGS", "monthlyFee", 2.5, "interestRate", .035, "active", true));
    }
    private Map<String, Object> article(String marker, boolean published) {
        return new HashMap<>(Map.of("category", "CARDS", "title", "Procédure " + marker,
                "content", "Contenu " + marker, "tags", List.of("carte", "test"), "published", published));
    }
    private static String suffix() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }
    private UUID customer() {
        UUID id = jdbc.queryForObject("insert into customer(external_ref,first_name,last_name,region) values(?,?,?,?) returning id",
                UUID.class, "REF-" + suffix(), "Client", "Reference", "Paris"); customerIds.add(id); return id;
    }

    @Test void roleAndAuthenticationAreEnforcedForEveryResource() throws Exception {
        for (String path : List.of("products", "knowledge-articles", "sla-policies", "routing-rules")) {
            call(get(BASE + path), 401);
            for (UserRole role : List.of(UserRole.CUSTOMER, UserRole.ADVISOR, UserRole.SUPERVISOR)) {
                var who = bearer(validToken(UUID.randomUUID(), "caller@roles.test", role));
                call(get(BASE + path).with(who), 403);
                call(post(BASE + path).with(who).contentType(MediaType.APPLICATION_JSON).content("{}"), 403);
                call(put(BASE + path + "/" + UUID.randomUUID()).with(who).contentType(MediaType.APPLICATION_JSON).content("{}"), 403);
                call(delete(BASE + path + "/" + UUID.randomUUID()).with(who).param("expectedVersion", "1"), 403);
            }
            assertThat(call(get(BASE + path).with(admin), 200).isArray()).isTrue();
        }
        assertThat(call(get(BASE + "routing-rules/options").with(admin), 200).path("skills").size()).isGreaterThanOrEqualTo(4);
    }

    @Test void productLifecycleIsPersistedAndDuplicateCodeIsAConflict() throws Exception {
        String code = "CAT_" + suffix(); Map<String, Object> input = product(code.toLowerCase());
        JsonNode first = create("products", "banking_product", input); String id = first.path("id").asText();
        assertThat(first.path("code").asText()).isEqualTo(code);
        call(body(post(BASE + "products"), product(code)), 409);
        input.put("code", code); input.put("name", "Offre modifiée"); input.put("active", false);
        JsonNode edited = call(body(put(BASE + "products/" + id), input), 200);
        assertThat(edited.path("active").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select name from banking_product where id=?", String.class, UUID.fromString(id))).isEqualTo("Offre modifiée");
        assertThat(call(get(BASE + "products").with(admin).param("q", code), 200).size()).isEqualTo(1);
        call(delete(BASE + "products/" + id).with(admin), 204);
        assertThat(call(get(BASE + "products").with(admin).param("q", code), 200).size()).isZero();
    }

    @Test void usedProductMustBeWithdrawnRatherThanDeletedOrRecategorized() throws Exception {
        Map<String, Object> input = product("LINK_" + suffix()); JsonNode item = create("products", "banking_product", input);
        UUID id = UUID.fromString(item.path("id").asText()); UUID customer = customer();
        jdbc.update("insert into account(customer_id,product_id,iban,status,opened_at) values(?,?,?,'ACTIVE',current_date)", customer, id, "FR76" + suffix());
        call(delete(BASE + "products/" + id).with(admin), 409);
        input.put("category", "MORTGAGE"); call(body(put(BASE + "products/" + id), input), 409);
        input.put("category", "SAVINGS"); input.put("active", false); call(body(put(BASE + "products/" + id), input), 200);
        assertThat(jdbc.queryForObject("select count(*) from account where product_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void productNumericAndTextBoundsAreRejectedRatherThanRounded() throws Exception {
        var input = product("BOUNDS_" + suffix());
        input.put("monthlyFee", .001); call(body(post(BASE + "products"), input), 400);
        input.put("monthlyFee", -1); call(body(post(BASE + "products"), input), 400);
        input.put("monthlyFee", 0); input.put("interestRate", 1.01); call(body(post(BASE + "products"), input), 400);
        input.put("interestRate", 0); input.put("name", "   "); call(body(post(BASE + "products"), input), 400);
        input.put("name", "Valid"); input.put("code", "bad code"); call(body(post(BASE + "products"), input), 400);
        call(body(put(BASE + "products/" + UUID.randomUUID()), product("MISSING")), 404);
    }

    @Test void articlesSupportDraftPublishVersionProtectionAndStaleVectorInvalidation() throws Exception {
        String marker = "KB-" + suffix(); var input = article(marker, false);
        JsonNode draft = create("knowledge-articles", "kb_article", input); String id = draft.path("id").asText();
        var advisor = bearer(validToken(UUID.randomUUID(), "reader@kb.test", UserRole.ADVISOR));
        assertThat(call(get("/api/v1/kb/articles").with(advisor).param("q", marker), 200).path("articles").size()).isZero();
        input.put("published", true); input.put("expectedVersion", 1);
        JsonNode published = call(body(put(BASE + "knowledge-articles/" + id), input), 200);
        assertThat(published.path("version").asInt()).isEqualTo(2);
        assertThat(call(get("/api/v1/kb/articles").with(advisor).param("q", marker), 200).path("articles").size()).isEqualTo(1);
        jdbc.update("insert into kb_chunk(article_id,chunk_index,content) values(?,0,'Old indexed content')", UUID.fromString(id));
        input.put("content", "Nouvelle procédure " + marker); input.put("expectedVersion", 2);
        JsonNode edited = call(body(put(BASE + "knowledge-articles/" + id), input), 200);
        assertThat(edited.path("semanticIndexed").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from kb_chunk where article_id=?", Integer.class, UUID.fromString(id))).isZero();
        call(body(put(BASE + "knowledge-articles/" + id), input), 409);
        call(delete(BASE + "knowledge-articles/" + id).with(admin).param("expectedVersion", "2"), 409);
        call(delete(BASE + "knowledge-articles/" + id).with(admin).param("expectedVersion", "3"), 204);
    }

    @Test void articleSearchTreatsWildcardsLiterallyAndRejectsOversizedBodies() throws Exception {
        var input = article("literal_" + suffix(), true); create("knowledge-articles", "kb_article", input);
        assertThat(call(get(BASE + "knowledge-articles").with(admin).param("q", "%"), 200).size()).isZero();
        input.put("content", "x".repeat(50001)); call(body(post(BASE + "knowledge-articles"), input), 400);
        input.put("content", "Valid"); input.put("tags", List.of(" ")); call(body(post(BASE + "knowledge-articles"), input), 400);
    }

    @Test void routingRulesActuallyChangeNewContactQueueAndInactiveRulesDoNot() throws Exception {
        Map<String, Object> rule = new HashMap<>(Map.of("name", "Route " + suffix(), "intent", "CARD", "skill", "FRAUD", "priority", 0, "active", true));
        JsonNode createdRule = create("routing-rules", "routing_rule", rule); UUID owner = customer();
        assertThat(conversations.open(owner, "ACCOUNTS", Intent.CARD, Channel.CHAT, Instant.now()).skill()).isEqualTo("FRAUD");
        assertThat(conversations.open(owner, "ACCOUNTS", Intent.BALANCE, Channel.CHAT, Instant.now()).skill()).isEqualTo("ACCOUNTS");
        rule.put("active", false); call(body(put(BASE + "routing-rules/" + createdRule.path("id").asText()), rule), 200);
        assertThat(conversations.open(owner, "ACCOUNTS", Intent.CARD, Channel.CHAT, Instant.now()).skill()).isEqualTo("ACCOUNTS");
        call(delete(BASE + "routing-rules/" + createdRule.path("id").asText()).with(admin), 204);
    }

    @Test void unknownRoutingConditionsAreNotSilentlyExecuted() throws Exception {
        var rule = Map.of("name", "Advanced " + suffix(), "intent", "OTHER", "skill", "FRAUD", "priority", 0, "active", true);
        JsonNode item = create("routing-rules", "routing_rule", rule); UUID id = UUID.fromString(item.path("id").asText());
        jdbc.update("update routing_rule set conditions='{\"future_predicate\":true}'::jsonb where id=?", id);
        assertThat(conversations.open(customer(), "CARDS", Intent.OTHER, Channel.CHAT, Instant.now()).skill()).isEqualTo("CARDS");
        call(body(put(BASE + "routing-rules/" + id), rule), 409);
        var invalid = new HashMap<String, Object>(rule); invalid.put("priority", -1); call(body(post(BASE + "routing-rules"), invalid), 400);
    }

    @Test void slaPoliciesPreventAmbiguousDuplicatesAndLossOfCoverage() throws Exception {
        String code = "SLA_" + suffix();
        UUID skill = jdbc.queryForObject("insert into skill(code,label) values(?,?) returning id", UUID.class, code, "Test SLA"); skillIds.add(skill);
        var input = new HashMap<String, Object>(Map.of("skill", code, "targetSeconds", 45, "targetRatio", .9, "active", true));
        JsonNode item = create("sla-policies", "sla_policy", input); String id = item.path("id").asText();
        call(body(post(BASE + "sla-policies"), input), 409);
        input.put("targetSeconds", 30); call(body(put(BASE + "sla-policies/" + id), input), 200);
        input.put("active", false); call(body(put(BASE + "sla-policies/" + id), input), 409);
        call(delete(BASE + "sla-policies/" + id).with(admin), 409);
        assertThat(jdbc.queryForObject("select active from sla_policy where id=?", Boolean.class, UUID.fromString(id))).isTrue();
        assertThat(jdbc.queryForObject("select target_seconds from sla_policy where id=?", Integer.class, UUID.fromString(id))).isEqualTo(30);
        input.put("active", true); input.put("targetRatio", .9999); call(body(put(BASE + "sla-policies/" + id), input), 400);
    }
}
