package com.callverse.internal;

import com.callverse.core.domain.entities.Contract;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Invoice;
import com.callverse.core.domain.entities.KbArticle;
import com.callverse.core.domain.entities.NetworkIncident;
import com.callverse.core.domain.entities.Plan;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.ContractStatus;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.InvoiceStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Rows for the {@code /internal} tool tests, persisted through JPA inside the test's transaction.
 *
 * <p>{@code V2__seed_reference.sql} seeds reference data only — plans, skills, criteria, accounts —
 * and no customer, contract, invoice, conversation, incident or article. Each test therefore builds
 * exactly the rows it reasons about, and the surrounding {@code @Transactional} rolls them back.
 * One builder, so that the six tool tests do not each invent their own idea of a valid row.
 */
final class InternalFixtures {

    private final EntityManager em;

    InternalFixtures(EntityManager em) {
        this.em = em;
    }

    Customer customer(String zone, boolean simulated) {
        Customer customer = new Customer();
        customer.setExternalRef("EXT-" + UUID.randomUUID().toString().substring(0, 12));
        customer.setFirstName("Amira");
        customer.setLastName("Ben Salem");
        customer.setPhone("+33600000000");
        customer.setZone(zone);
        customer.setTenureMonths(26);
        // HIGH on purpose: tests assert it never reaches the agent.
        customer.setChurnRisk(ChurnRisk.HIGH);
        customer.setSimulated(simulated);
        return persist(customer);
    }

    Contract contract(Customer customer, String planCode) {
        Plan plan =
                em.createQuery("select p from Plan p where p.code = :code", Plan.class)
                        .setParameter("code", planCode)
                        .getSingleResult();
        Contract contract = new Contract();
        contract.setCustomer(customer);
        contract.setPlan(plan);
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setStartedAt(LocalDate.of(2024, 7, 1));
        customer.getContracts().add(contract);
        return persist(contract);
    }

    Invoice invoice(Contract contract, LocalDate periodStart, String amount, InvoiceStatus status) {
        Invoice invoice = new Invoice();
        invoice.setContract(contract);
        invoice.setPeriodStart(periodStart);
        invoice.setPeriodEnd(periodStart.plusMonths(1).minusDays(1));
        invoice.setAmount(new BigDecimal(amount));
        invoice.setStatus(status);
        return persist(invoice);
    }

    Conversation conversation(Customer customer, ConversationStatus status) {
        Conversation conversation = new Conversation();
        conversation.setCustomer(customer);
        conversation.setStatus(status);
        return persist(conversation);
    }

    NetworkIncident incident(String zone, UUID runId, boolean resolved) {
        NetworkIncident incident = new NetworkIncident();
        incident.setZone(zone);
        incident.setType("FIBER_CUT");
        incident.setSeverity((short) 4);
        incident.setStartedAt(Instant.parse("2026-09-26T07:30:00Z"));
        incident.setEstimatedEnd(Instant.parse("2026-09-26T13:00:00Z"));
        incident.setAffectedCount(1200);
        incident.setRunId(runId);
        if (resolved) {
            incident.setResolvedAt(Instant.parse("2026-09-26T09:00:00Z"));
        }
        return persist(incident);
    }

    KbArticle article(String category, String title, String content, boolean published) {
        KbArticle article = new KbArticle();
        article.setCategory(category);
        article.setTitle(title);
        article.setContent(content);
        article.setTags(new String[] {category.toLowerCase()});
        article.setPublished(published);
        article.setUpdatedAt(Instant.parse("2026-09-20T10:00:00Z"));
        return persist(article);
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }
}
