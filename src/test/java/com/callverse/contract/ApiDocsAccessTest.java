package com.callverse.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.persistence.AbstractPersistenceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Outside {@code dev}, the documentation is open and the API is not. Anyone can open Swagger UI and
 * read the contract (the frontend generates its types from it); every business endpoint still needs
 * a bearer token, which Swagger's Authorize button supplies after a call to login.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ApiDocsAccessTest extends AbstractPersistenceTest {

    @Autowired MockMvc mockMvc;

    private int status(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("Swagger UI opens without a token")
    void swaggerUiIsOpen() throws Exception {
        assertThat(status("/swagger-ui/index.html")).isEqualTo(200);
        assertThat(status("/swagger-ui.html")).isIn(200, 302);
    }

    @Test
    @DisplayName("the OpenAPI document is readable without a token")
    void apiDocsAreOpen() throws Exception {
        assertThat(status("/v3/api-docs")).isEqualTo(200);
        assertThat(status("/v3/api-docs/swagger-config")).isEqualTo(200);
    }

    @Test
    @DisplayName("business endpoints still require a token")
    void apiStillRequiresAToken() throws Exception {
        assertThat(status("/api/v1/customers/00000000-0000-0000-0000-000000000000")).isEqualTo(401);
        assertThat(status("/api/v1/service-incidents")).isEqualTo(401);
    }
}
