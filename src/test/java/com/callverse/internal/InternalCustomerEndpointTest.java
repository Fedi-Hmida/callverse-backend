package com.callverse.internal;

import static com.callverse.auth.AuthenticatedRequests.serviceKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class InternalCustomerEndpointTest extends AbstractPersistenceTest {

    private static final String PATH = "/internal/customers/{id}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;

    @Test
    @Transactional
    void serviceCanReadCustomerWithMaskedAccountData() throws Exception {
        String rawIban =
                ("FR7630006000011234567890" + UUID.randomUUID().toString().replace("-", "").substring(0, 4))
                        .toUpperCase(Locale.ROOT);
        Customer customer = new Customer();
        customer.setExternalRef("CUST-INTERNAL-READ");
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setRegion("Marseille");
        customer.setSegment(CustomerSegment.AFFLUENT);
        customer.setTenureMonths(18);
        em.persist(customer);

        BankingProduct product = em.createQuery(
                        "select p from BankingProduct p where p.code = 'CUR_ESSENTIAL'", BankingProduct.class)
                .getSingleResult();
        Account account = new Account();
        account.setCustomer(customer);
        account.setProduct(product);
        account.setIban(rawIban);
        account.setBalance(new BigDecimal("1523.40"));
        account.setStatus(AccountStatus.ACTIVE);
        account.setOpenedAt(LocalDate.of(2024, 3, 1));
        em.persist(account);
        em.flush();
        em.clear();

        MvcResult result = mockMvc.perform(get(PATH, customer.getId()).with(serviceKey())).andReturn();
        String raw = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).as("body was: %s", raw).isEqualTo(200);
        JsonNode body = objectMapper.readTree(raw);
        assertThat(body.get("id").asText()).isEqualTo(customer.getId().toString());
        assertThat(body.get("segment").asText()).isEqualTo("AFFLUENT");
        assertThat(body.get("accounts").get(0).get("maskedIban").asText())
                .isEqualTo("FR76 **** **** " + rawIban.substring(rawIban.length() - 4));
        assertThat(raw).doesNotContain(rawIban);
        assertThat(body.has("phone")).isFalse();
        assertThat(body.has("churnRisk")).isFalse();
        assertThat(body.has("isSimulated")).isFalse();
    }
}
