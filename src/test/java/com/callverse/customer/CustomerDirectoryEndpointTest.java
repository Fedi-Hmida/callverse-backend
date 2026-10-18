package com.callverse.customer;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class CustomerDirectoryEndpointTest extends AbstractPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    private static final String PATH = "/api/v1/customers/directory";

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ADVISOR", "SUPERVISOR", "ADMIN"})
    void staffCanBrowse(UserRole role) throws Exception {
        var response = mvc.perform(get(PATH).with(bearer(validToken(UUID.randomUUID(), "directory@test.local", role)))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void clientsAndAnonymousCannotBrowseOtherCustomers() throws Exception {
        assertThat(mvc.perform(get(PATH)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(PATH).with(bearer(validToken(UUID.randomUUID(), "customer@test.local", UserRole.CUSTOMER))))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Transactional
    void searchesByNameExcludesSyntheticCustomersAndPaginatesWithoutPrivateFields() throws Exception {
        for (int i = 0; i < 23; i++) {
            var customer = new Customer();
            customer.setFirstName("DirectoryProbe"); customer.setLastName("Person " + i);
            customer.setExternalRef("DIRECTORY-" + i); customer.setRegion("Lyon");
            customer.setSimulated(i >= 21); customer.setPhone("private-phone"); em.persist(customer);
        }
        em.flush();
        var auth = bearer(validToken(UUID.randomUUID(), "advisor@test.local", UserRole.ADVISOR));
        var first = mvc.perform(get(PATH).param("q", " directoryprobe ").with(auth)).andReturn().getResponse();
        assertThat(first.getStatus()).isEqualTo(200);
        var body = json.readTree(first.getContentAsString());
        assertThat(body.get("content").size()).isEqualTo(20);
        assertThat(body.get("hasNext").asBoolean()).isTrue();
        assertThat(first.getContentAsString()).doesNotContain("private-phone", "externalRef", "accounts", "churnRisk");
        var second = mvc.perform(get(PATH).param("q", "DirectoryProbe").param("page", "1").with(auth)).andReturn().getResponse();
        var last = json.readTree(second.getContentAsString());
        assertThat(last.get("content").size()).isEqualTo(1);
        assertThat(last.get("hasNext").asBoolean()).isFalse();
        assertThat(body.get("content").findValuesAsText("id")).doesNotContain(last.get("content").get(0).get("id").asText());
        assertThat(mvc.perform(get(PATH).param("page", "-1").with(auth)).andReturn().getResponse().getStatus()).isEqualTo(400);
    }
}
