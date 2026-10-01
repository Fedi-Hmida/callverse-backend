package com.callverse.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The OpenAPI document is a published contract: the Next.js client generates its TypeScript types
 * from it. These tests pin what the frontend binds to, so a change shows up here before it breaks
 * a build in another repository.
 *
 * <p>{@code openapi.yaml} at the repository root is the committed copy (brief 01 requires one). The
 * drift test fails when the live document no longer matches it; regenerate with {@code make openapi}
 * and commit the diff, which is then the reviewable record of the contract change.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OpenApiContractTest extends AbstractPersistenceTest {

    static final Path COMMITTED = Path.of("openapi.yaml");

    /** Every operation the frontend may bind to, by its fixed id. */
    static final Set<String> OPERATION_IDS = Set.of(
            "login",
            "getCurrentUser",
            "getHealthStatus",
            "getCustomer",
            "findCustomerByReference",
            "listCustomerTransactions",
            "listActiveServiceIncidents",
            "searchKnowledgeArticles",
            "openTicket",
            "blockCard",
            "escalateConversation");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private JsonNode document() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("every operation carries an explicit, fixed operationId, and no other operation exists")
    void operationIdsArePinned() throws Exception {
        Set<String> found = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        document().get("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(op -> {
            JsonNode id = op.getValue().get("operationId");
            if (id == null) {
                missing.add(op.getKey().toUpperCase() + " " + path.getKey());
            } else {
                found.add(id.asText());
            }
        }));

        assertThat(missing).as("operations without an operationId").isEmpty();
        assertThat(found).containsExactlyInAnyOrderElementsOf(OPERATION_IDS);
    }

    @Test
    @DisplayName("response fields the frontend relies on are declared required, so generated types are not optional")
    void responseFieldsAreRequired() throws Exception {
        JsonNode schemas = document().get("components").get("schemas");

        assertThat(required(schemas, "CustomerResponse"))
                .contains("id", "externalRef", "firstName", "lastName", "region", "segment", "tenureMonths", "accounts");
        assertThat(required(schemas, "TokenResponse")).contains("token", "expiresAt", "role");
        assertThat(required(schemas, "ErrorResponse")).contains("timestamp", "status", "code", "message", "path");
        assertThat(required(schemas, "CardResponse")).contains("id", "panLast4", "status");
        assertThat(schemas.has("CustomerAccount")).as("nested schemas carry a qualified name").isTrue();
    }

    @Test
    @DisplayName("the committed openapi.yaml matches the live document")
    void committedDocumentMatches() throws Exception {
        String live = mockMvc.perform(get("/v3/api-docs.yaml")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(COMMITTED, live, StandardCharsets.UTF_8);
        }

        assertThat(Files.exists(COMMITTED)).as("openapi.yaml is missing: run make openapi").isTrue();
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("the API contract changed: run make openapi and commit openapi.yaml")
                .isEqualTo(live.replace("\r\n", "\n"));
    }

    private static List<String> required(JsonNode schemas, String name) {
        List<String> fields = new ArrayList<>();
        JsonNode schema = schemas.get(name);
        assertThat(schema).as("schema %s exists", name).isNotNull();
        if (schema.has("required")) {
            schema.get("required").forEach(f -> fields.add(f.asText()));
        }
        return fields;
    }
}
