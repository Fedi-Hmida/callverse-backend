package com.callverse.internal;

import static com.callverse.auth.AuthenticatedRequests.serviceKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.BankTransaction;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class InternalTransactionsEndpointTest extends AbstractPersistenceTest {

    private static final String PATH = "/internal/customers/{id}/transactions";
    private static final String RAW_IBAN = "DE89370400440532013000";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;

    @Test
    @Transactional
    void serviceReadsTransactionsWithMaskedFreeTextIbans() throws Exception {
        Customer customer = new Customer();
        customer.setExternalRef("CUST-INTERNAL-TRANSACTIONS");
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setRegion("Marseille");
        em.persist(customer);

        BankingProduct product = em.createQuery(
                        "select p from BankingProduct p where p.code = 'CUR_ESSENTIAL'", BankingProduct.class)
                .getSingleResult();
        Account account = new Account();
        account.setCustomer(customer);
        account.setProduct(product);
        account.setIban("FR76" + UUID.randomUUID().toString().replace("-", "").substring(0, 23));
        account.setStatus(AccountStatus.ACTIVE);
        account.setOpenedAt(LocalDate.of(2024, 3, 1));
        em.persist(account);

        BankTransaction transaction = new BankTransaction();
        transaction.setAccount(account);
        transaction.setType(TransactionType.TRANSFER_OUT);
        transaction.setAmount(new BigDecimal("-10.00"));
        transaction.setLabel("Virement " + RAW_IBAN);
        transaction.setCounterparty("Compte " + RAW_IBAN);
        transaction.setStatus(TransactionStatus.BOOKED);
        transaction.setBookedAt(Instant.parse("2026-09-01T08:00:00Z"));
        em.persist(transaction);
        em.flush();
        em.clear();

        MvcResult result =
                mockMvc.perform(get(PATH, customer.getId()).param("n", "1").with(serviceKey())).andReturn();
        String raw = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).as("body was: %s", raw).isEqualTo(200);
        JsonNode body = objectMapper.readTree(raw);
        assertThat(body.get("customerId").asText()).isEqualTo(customer.getId().toString());
        assertThat(body.get("transactions")).hasSize(1);
        JsonNode movement = body.get("transactions").get(0);
        assertThat(movement.get("type").asText()).isEqualTo("TRANSFER_OUT");
        assertThat(movement.get("label").asText()).isEqualTo("Virement DE89 **** **** 3000");
        assertThat(movement.get("counterparty").asText()).isEqualTo("Compte DE89 **** **** 3000");
        assertThat(raw).doesNotContain(RAW_IBAN);
    }
}
