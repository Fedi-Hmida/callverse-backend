package com.callverse.workspace;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.tokenForExistingAccount;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.BankingService;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The advisor workspace: every route an advisor needs to handle a banking call, against the
 * deny-by-default {@code prod} chain, so that a passing test proves both the chain rule and the
 * role gate.
 *
 * <p>Every route is checked for its success case and for at least one refused role. Every write is
 * flushed and re-read through a query, so a test cannot pass on a row that never reached the
 * database.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Transactional
class AdvisorWorkspaceEndpointTest extends AbstractPersistenceTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager em;

    private WorkspaceFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new WorkspaceFixtures(em);
    }

    // ------------------------------------------------------------------ credentials

    static RequestPostProcessor as(UserRole role) {
        return bearer(validToken(UUID.randomUUID(), role.name().toLowerCase() + "@callverse.local", role));
    }

    /** A token for a login that exists, so ownership checks have someone to match. */
    static RequestPostProcessor as(AppUser user) {
        return bearer(tokenForExistingAccount(user.getId(), user.getEmail(), user.getRole()));
    }

    static RequestPostProcessor anonymous() {
        return request -> request;
    }

    private JsonNode call(RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as("body was: %s", body).isEqualTo(expectedStatus);
        return body.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(body);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    // ------------------------------------------------------------------ customer lookup

    @Nested
    @DisplayName("GET /api/v1/customers?externalRef=")
    class CustomerLookup {

        @Test
        @DisplayName("an ADVISOR finds a customer by reference, with every IBAN masked")
        void advisorFindsCustomer() throws Exception {
            String ref = "CUST-" + WorkspaceFixtures.suffix();
            String iban = WorkspaceFixtures.iban();
            Customer customer = fixtures.customer(ref, "Marseille");
            fixtures.account(customer, iban);
            flushAndClear();

            MvcResult result = mockMvc.perform(
                            get("/api/v1/customers").param("externalRef", ref).with(as(UserRole.ADVISOR)))
                    .andReturn();
            String raw = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).as(raw).isEqualTo(200);

            JsonNode body = objectMapper.readTree(raw);
            assertThat(body.get("id").asText()).isEqualTo(customer.getId().toString());
            assertThat(body.get("accounts").get(0).get("maskedIban").asText()).endsWith(iban.substring(iban.length() - 4));
            assertThat(raw).doesNotContain(iban);
        }

        @Test
        @DisplayName("each account lists its cards, so an advisor can act on one: last four digits only")
        void accountsListTheirCards() throws Exception {
            String ref = "CUST-" + WorkspaceFixtures.suffix();
            Customer customer = fixtures.customer(ref, "Marseille");
            Card card = fixtures.card(fixtures.account(customer, WorkspaceFixtures.iban()), CardStatus.ACTIVE);
            flushAndClear();

            JsonNode cards = call(get("/api/v1/customers").param("externalRef", ref).with(as(UserRole.ADVISOR)), 200)
                    .get("accounts").get(0).get("cards");

            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).get("id").asText()).isEqualTo(card.getId().toString());
            assertThat(cards.get(0).get("panLast4").asText()).isEqualTo("4242");
            assertThat(cards.get(0).get("status").asText()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("an unknown reference is 404, not an empty body")
        void unknownReferenceIsNotFound() throws Exception {
            ErrorEnvelope.assertConforms(
                    call(get("/api/v1/customers").param("externalRef", "NOPE-" + WorkspaceFixtures.suffix())
                            .with(as(UserRole.ADVISOR)), 404),
                    404,
                    "RESOURCE_NOT_FOUND");
        }

        @Test
        @DisplayName("the reference is required")
        void referenceIsRequired() throws Exception {
            ErrorEnvelope.assertConforms(
                    call(get("/api/v1/customers").with(as(UserRole.ADVISOR)), 400), 400, "VALIDATION_FAILED");
        }

        @Test
        @DisplayName("a CUSTOMER is refused: staff only")
        void customerIsRefused() throws Exception {
            ErrorEnvelope.assertConforms(
                    call(get("/api/v1/customers").param("externalRef", "X").with(as(UserRole.CUSTOMER)), 403),
                    403,
                    "ACCESS_DENIED");
        }
    }

    // ------------------------------------------------------------------ transactions

    @Nested
    @DisplayName("GET /api/v1/customers/{id}/transactions")
    class Transactions {

        private Customer customer;

        @BeforeEach
        void seed() {
            customer = fixtures.customer("TXN-" + WorkspaceFixtures.suffix(), "Marseille");
            Account account = fixtures.account(customer, WorkspaceFixtures.iban());
            Instant t0 = Instant.parse("2026-09-01T08:00:00Z");
            fixtures.movement(account, "oldest", null, t0);
            fixtures.movement(account, "middle", null, t0.plusSeconds(60));
            fixtures.movement(account, "newest", "Loyer DE89370400440532013000", t0.plusSeconds(120));
            flushAndClear();
        }

        @Test
        @DisplayName("newest first, bounded by count, and an IBAN inside a counterparty is masked")
        void newestFirstBoundedAndMasked() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/customers/{id}/transactions", customer.getId())
                            .param("count", "2")
                            .with(as(UserRole.ADVISOR)))
                    .andReturn();
            String raw = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).as(raw).isEqualTo(200);

            JsonNode transactions = objectMapper.readTree(raw).get("transactions");
            assertThat(transactions).hasSize(2);
            assertThat(transactions.get(0).get("label").asText()).isEqualTo("newest");
            assertThat(transactions.get(1).get("label").asText()).isEqualTo("middle");
            assertThat(transactions.get(0).get("counterparty").asText()).contains("DE89 **** **** 3000");
            assertThat(raw).doesNotContain("DE89370400440532013000");
        }

        @Test
        @DisplayName("ten by default, fifty at most: 51 is refused")
        void countIsBounded() throws Exception {
            ErrorEnvelope.assertConforms(
                    call(get("/api/v1/customers/{id}/transactions", customer.getId())
                            .param("count", "51").with(as(UserRole.ADVISOR)), 400),
                    400,
                    "VALIDATION_FAILED");
        }

        @Test
        @DisplayName("an unknown customer is 404, never an empty history")
        void unknownCustomerIsNotFound() throws Exception {
            ErrorEnvelope.assertConforms(
                    call(get("/api/v1/customers/{id}/transactions", UUID.randomUUID()).with(as(UserRole.ADVISOR)), 404),
                    404,
                    "RESOURCE_NOT_FOUND");
        }

        @Test
        @DisplayName("a CUSTOMER is refused until ownership rules exist")
        void customerIsRefused() throws Exception {
            call(get("/api/v1/customers/{id}/transactions", customer.getId()).with(as(UserRole.CUSTOMER)), 403);
        }
    }

    // ------------------------------------------------------------------ service incidents

    @Nested
    @DisplayName("GET /api/v1/service-incidents")
    class ServiceIncidents {

        private String region;

        @BeforeEach
        void seed() {
            region = "REG-" + WorkspaceFixtures.suffix();
            fixtures.incident(BankingService.CARD_PAYMENTS, region, null, "regional live");
            fixtures.incident(BankingService.ONLINE_BANKING, null, null, "national live");
            fixtures.incident(BankingService.ATM_NETWORK, "REG-elsewhere", null, "other region live");
            fixtures.incident(BankingService.TRANSFERS, region, UUID.randomUUID(), "simulated");
            flushAndClear();
        }

        @Test
        @DisplayName("a region's outages plus national ones, never a simulated one; any authenticated role")
        void regionalPlusNationalLiveOnly() throws Exception {
            JsonNode body = call(get("/api/v1/service-incidents").param("region", region).with(as(UserRole.CUSTOMER)), 200);

            assertThat(body.findValuesAsText("description"))
                    .contains("regional live", "national live")
                    .doesNotContain("other region live", "simulated");
            assertThat(body.toString()).as("a run id never leaves the live route").doesNotContain("runId");
        }

        @Test
        @DisplayName("anonymous callers are refused")
        void anonymousIsRefused() throws Exception {
            call(get("/api/v1/service-incidents").with(anonymous()), 401);
        }

        @Test
        @DisplayName("a blank region is a mistake, not a wildcard")
        void blankRegionIsRefused() throws Exception {
            ErrorEnvelope.assertConforms(
                    call(get("/api/v1/service-incidents").param("region", "  ").with(as(UserRole.ADVISOR)), 400),
                    400,
                    "VALIDATION_FAILED");
        }
    }

    // ------------------------------------------------------------------ knowledge base

    @Nested
    @DisplayName("GET /api/v1/kb/articles")
    class KnowledgeBase {

        @Test
        @DisplayName("published articles only, for staff")
        void publishedOnly() throws Exception {
            String marker = "Opposition " + WorkspaceFixtures.suffix();
            fixtures.article(marker + " publiee", true);
            fixtures.article(marker + " brouillon", false);
            flushAndClear();

            JsonNode body = call(get("/api/v1/kb/articles").param("q", marker).with(as(UserRole.ADVISOR)), 200);

            assertThat(body.findValuesAsText("title")).containsExactly(marker + " publiee");
        }

        @Test
        @DisplayName("a one-character query is refused")
        void tooShortQueryIsRefused() throws Exception {
            ErrorEnvelope.assertConforms(
                    call(get("/api/v1/kb/articles").param("q", "a").with(as(UserRole.ADVISOR)), 400),
                    400,
                    "VALIDATION_FAILED");
        }

        @Test
        @DisplayName("a CUSTOMER is refused: internal procedures are for staff")
        void customerIsRefused() throws Exception {
            call(get("/api/v1/kb/articles").param("q", "carte").with(as(UserRole.CUSTOMER)), 403);
        }
    }

    // ------------------------------------------------------------------ tickets

    @Nested
    @DisplayName("POST /api/v1/tickets")
    class Tickets {

        private String body(UUID customerId, UUID conversationId) {
            return """
                   {"customerId": "%s", %s "category": "FRAUD", "title": "Paiements inconnus a l'etranger",
                    "description": "Deux paiements non reconnus", "severity": 2}
                   """.formatted(customerId, conversationId == null ? "" : "\"conversationId\": \"" + conversationId + "\",");
        }

        @Test
        @DisplayName("an ADVISOR opens a ticket; it is OPEN and really stored")
        void ticketIsStored() throws Exception {
            Customer customer = fixtures.customer("TKT-" + WorkspaceFixtures.suffix(), "Marseille");
            Conversation conversation = fixtures.conversation(customer, ConversationStatus.ACTIVE);
            flushAndClear();

            JsonNode created = call(post("/api/v1/tickets").contentType(MediaType.APPLICATION_JSON)
                    .content(body(customer.getId(), conversation.getId())).with(as(UserRole.ADVISOR)), 201);

            assertThat(created.get("status").asText()).isEqualTo("OPEN");
            flushAndClear();
            String stored = em.createQuery("select t.status from Ticket t where t.id = :id", Object.class)
                    .setParameter("id", UUID.fromString(created.get("id").asText()))
                    .getSingleResult()
                    .toString();
            assertThat(stored).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("another customer's conversation is refused and nothing is stored")
        void mismatchIsRefused() throws Exception {
            Customer owner = fixtures.customer("TKT-" + WorkspaceFixtures.suffix(), "Marseille");
            Customer other = fixtures.customer("TKT-" + WorkspaceFixtures.suffix(), "Lyon");
            Conversation othersConversation = fixtures.conversation(other, ConversationStatus.ACTIVE);
            flushAndClear();

            ErrorEnvelope.assertConforms(
                    call(post("/api/v1/tickets").contentType(MediaType.APPLICATION_JSON)
                            .content(body(owner.getId(), othersConversation.getId())).with(as(UserRole.ADVISOR)), 400),
                    400,
                    "CONVERSATION_CUSTOMER_MISMATCH");
            Long rows = em.createQuery("select count(t) from Ticket t where t.customer.id = :id", Long.class)
                    .setParameter("id", owner.getId())
                    .getSingleResult();
            assertThat(rows).isZero();
        }

        @Test
        @DisplayName("a CUSTOMER cannot open tickets through the staff route")
        void customerIsRefused() throws Exception {
            call(post("/api/v1/tickets").contentType(MediaType.APPLICATION_JSON)
                    .content(body(UUID.randomUUID(), null)).with(as(UserRole.CUSTOMER)), 403);
        }

        @Test
        @DisplayName("a body missing its title is refused at the edge")
        void invalidBodyIsRefused() throws Exception {
            ErrorEnvelope.assertConforms(
                    call(post("/api/v1/tickets").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"customerId\": \"" + UUID.randomUUID() + "\", \"category\": \"FRAUD\"}")
                            .with(as(UserRole.ADVISOR)), 400),
                    400,
                    "VALIDATION_FAILED");
        }
    }

    // ------------------------------------------------------------------ card blocking

    @Nested
    @DisplayName("POST /api/v1/cards/{id}/block")
    class CardBlocking {

        private static final String STOLEN = "{\"reason\": \"STOLEN\"}";

        private Card card(CardStatus status) {
            Customer customer = fixtures.customer("CRD-" + WorkspaceFixtures.suffix(), "Marseille");
            Card card = fixtures.card(fixtures.account(customer, WorkspaceFixtures.iban()), status);
            flushAndClear();
            return card;
        }

        @Test
        @DisplayName("an ADVISOR blocks an active card; status, reason and time are stored")
        void activeCardIsBlocked() throws Exception {
            Card card = card(CardStatus.ACTIVE);

            JsonNode body = call(post("/api/v1/cards/{id}/block", card.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(STOLEN).with(as(UserRole.ADVISOR)), 200);

            assertThat(body.get("status").asText()).isEqualTo("BLOCKED");
            assertThat(body.get("blockReason").asText()).isEqualTo("STOLEN");
            assertThat(body.get("panLast4").asText()).isEqualTo("4242");
            flushAndClear();
            Card stored = em.createQuery("select c from Card c where c.id = :id", Card.class)
                    .setParameter("id", card.getId())
                    .getSingleResult();
            assertThat(stored.getStatus()).isEqualTo(CardStatus.BLOCKED);
            assertThat(stored.getBlockedAt()).isNotNull();
        }

        @Test
        @DisplayName("blocking twice is idempotent: the first reason and time are kept")
        void blockingTwiceKeepsTheFirstBlock() throws Exception {
            Card card = card(CardStatus.ACTIVE);
            JsonNode first = call(post("/api/v1/cards/{id}/block", card.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(STOLEN).with(as(UserRole.ADVISOR)), 200);
            flushAndClear();

            JsonNode second = call(post("/api/v1/cards/{id}/block", card.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"LOST\"}").with(as(UserRole.ADVISOR)), 200);

            assertThat(second.get("blockReason").asText()).isEqualTo("STOLEN");
            assertThat(second.get("blockedAt").asText()).isEqualTo(first.get("blockedAt").asText());
        }

        @Test
        @DisplayName("an expired card cannot be blocked: 409")
        void expiredCardIsRefused() throws Exception {
            Card card = card(CardStatus.EXPIRED);
            ErrorEnvelope.assertConforms(
                    call(post("/api/v1/cards/{id}/block", card.getId()).contentType(MediaType.APPLICATION_JSON)
                            .content(STOLEN).with(as(UserRole.ADVISOR)), 409),
                    409,
                    "INVALID_STATE_TRANSITION");
        }

        @Test
        @DisplayName("an unknown card is 404")
        void unknownCardIsNotFound() throws Exception {
            call(post("/api/v1/cards/{id}/block", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                    .content(STOLEN).with(as(UserRole.ADVISOR)), 404);
        }

        @Test
        @DisplayName("a reason outside the vocabulary is refused")
        void unknownReasonIsRefused() throws Exception {
            Card card = card(CardStatus.ACTIVE);
            call(post("/api/v1/cards/{id}/block", card.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"BORED\"}").with(as(UserRole.ADVISOR)), 400);
        }

        @Test
        @DisplayName("a CUSTOMER cannot block through the staff route")
        void customerIsRefused() throws Exception {
            Card card = card(CardStatus.ACTIVE);
            call(post("/api/v1/cards/{id}/block", card.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(STOLEN).with(as(UserRole.CUSTOMER)), 403);
        }
    }

    // ------------------------------------------------------------------ escalations

    @Nested
    @DisplayName("POST /api/v1/conversations/{id}/escalations")
    class Escalations {

        private static final String REASON = "{\"reason\": \"Fraude suspectee, besoin d'un superviseur\"}";

        @Test
        @DisplayName("the conversation's ADVISOR escalates: 201, stored, conversation now ESCALATED; a repeat is 200")
        void advisorEscalatesIdempotently() throws Exception {
            Customer customer = fixtures.customer("ESC-" + WorkspaceFixtures.suffix(), "Marseille");
            AppUser karim = fixtures.user(UserRole.ADVISOR);
            Conversation conversation =
                    fixtures.conversation(customer, ConversationStatus.ACTIVE, fixtures.advisor(karim));
            flushAndClear();

            JsonNode first = call(post("/api/v1/conversations/{id}/escalations", conversation.getId())
                    .contentType(MediaType.APPLICATION_JSON).content(REASON).with(as(karim)), 201);
            assertThat(first.get("raisedBy").asText()).isEqualTo("ADVISOR");
            flushAndClear();

            Object storedBy = em.createQuery("select e.raisedBy from Escalation e where e.id = :id", Object.class)
                    .setParameter("id", UUID.fromString(first.get("id").asText()))
                    .getSingleResult();
            assertThat(storedBy.toString()).isEqualTo("ADVISOR");
            assertThat(em.find(Conversation.class, conversation.getId()).getStatus())
                    .as("raising the escalation moves the conversation, in the same transaction")
                    .isEqualTo(ConversationStatus.ESCALATED);

            JsonNode second = call(post("/api/v1/conversations/{id}/escalations", conversation.getId())
                    .contentType(MediaType.APPLICATION_JSON).content(REASON).with(as(karim)), 200);
            assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        }

        @Test
        @DisplayName("a resolved conversation cannot be escalated: 409")
        void resolvedConversationIsRefused() throws Exception {
            Customer customer = fixtures.customer("ESC-" + WorkspaceFixtures.suffix(), "Marseille");
            AppUser karim = fixtures.user(UserRole.ADVISOR);
            Conversation conversation =
                    fixtures.conversation(customer, ConversationStatus.RESOLVED, fixtures.advisor(karim));
            flushAndClear();

            ErrorEnvelope.assertConforms(
                    call(post("/api/v1/conversations/{id}/escalations", conversation.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(REASON).with(as(karim)), 409),
                    409,
                    "INVALID_STATE_TRANSITION");
        }

        @Test
        @DisplayName("an ACTIVE conversation already holding a pending escalation is moved to ESCALATED on retry: 200")
        void legacyPendingEscalationHeals() throws Exception {
            Customer customer = fixtures.customer("ESC-" + WorkspaceFixtures.suffix(), "Marseille");
            AppUser karim = fixtures.user(UserRole.ADVISOR);
            Conversation conversation =
                    fixtures.conversation(customer, ConversationStatus.ACTIVE, fixtures.advisor(karim));
            com.callverse.core.domain.entities.Escalation old = new com.callverse.core.domain.entities.Escalation();
            old.setConversation(conversation);
            old.setReason("raised before escalating moved the conversation");
            old.setRaisedBy(com.callverse.core.domain.enums.EscalationRaisedBy.ADVISOR);
            em.persist(old);
            flushAndClear();

            JsonNode body = call(post("/api/v1/conversations/{id}/escalations", conversation.getId())
                    .contentType(MediaType.APPLICATION_JSON).content(REASON).with(as(karim)), 200);
            assertThat(body.get("id").asText()).isEqualTo(old.getId().toString());
            flushAndClear();
            assertThat(em.find(Conversation.class, conversation.getId()).getStatus())
                    .isEqualTo(ConversationStatus.ESCALATED);
        }

        @Test
        @DisplayName("a simulation run's conversation cannot be escalated from the live route: 404")
        void simulatedConversationIsNotFound() throws Exception {
            Customer customer = fixtures.customer("ESC-" + WorkspaceFixtures.suffix(), "Marseille");
            AppUser karim = fixtures.user(UserRole.ADVISOR);
            Conversation conversation =
                    fixtures.conversation(customer, ConversationStatus.ACTIVE, fixtures.advisor(karim));
            conversation.setRunId(UUID.randomUUID());
            flushAndClear();

            call(post("/api/v1/conversations/{id}/escalations", conversation.getId())
                    .contentType(MediaType.APPLICATION_JSON).content(REASON).with(as(karim)), 404);
            assertThat(em.find(Conversation.class, conversation.getId()).getStatus())
                    .isEqualTo(ConversationStatus.ACTIVE);
        }

        @Test
        @DisplayName("another advisor escalating someone else's conversation is 404: not theirs to see")
        void anotherAdvisorIsNotFound() throws Exception {
            Customer customer = fixtures.customer("ESC-" + WorkspaceFixtures.suffix(), "Marseille");
            Conversation conversation = fixtures.conversation(
                    customer, ConversationStatus.ACTIVE, fixtures.advisor(fixtures.user(UserRole.ADVISOR)));
            AppUser lina = fixtures.user(UserRole.ADVISOR);
            fixtures.advisor(lina);
            flushAndClear();

            ErrorEnvelope.assertConforms(
                    call(post("/api/v1/conversations/{id}/escalations", conversation.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(REASON).with(as(lina)), 404),
                    404,
                    "RESOURCE_NOT_FOUND");
            assertThat(em.find(Conversation.class, conversation.getId()).getStatus())
                    .isEqualTo(ConversationStatus.ACTIVE);
        }

        @Test
        @DisplayName("a CUSTOMER cannot escalate through the staff route")
        void customerIsRefused() throws Exception {
            call(post("/api/v1/conversations/{id}/escalations", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(REASON).with(as(UserRole.CUSTOMER)), 403);
        }
    }

    // ------------------------------------------------------------------ who may do what

    @Nested
    @DisplayName("role matrix: agents act, admins administer, customers do neither")
    class RoleMatrix {

        private Customer customer;
        private Conversation conversation;
        private Card card;
        private AppUser karim;

        @BeforeEach
        void seed() {
            customer = fixtures.customer("ROLE-" + WorkspaceFixtures.suffix(), "Marseille");
            karim = fixtures.user(UserRole.ADVISOR);
            conversation = fixtures.conversation(customer, ConversationStatus.ACTIVE, fixtures.advisor(karim));
            card = fixtures.card(fixtures.account(customer, WorkspaceFixtures.iban()), CardStatus.ACTIVE);
            flushAndClear();
        }

        private RequestBuilder ticket(RequestPostProcessor who) {
            return post("/api/v1/tickets").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"customerId\": \"" + customer.getId() + "\", \"category\": \"CARD\", \"title\": \"t\"}")
                    .with(who);
        }

        private RequestBuilder block(RequestPostProcessor who) {
            return post("/api/v1/cards/{id}/block", card.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"LOST\"}").with(who);
        }

        private RequestBuilder escalate(RequestPostProcessor who) {
            return post("/api/v1/conversations/{id}/escalations", conversation.getId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"r\"}").with(who);
        }

        @Test
        @DisplayName("a SUPERVISOR may open a ticket and block a card")
        void supervisorActs() throws Exception {
            call(ticket(as(UserRole.SUPERVISOR)), 201);
            call(block(as(UserRole.SUPERVISOR)), 200);
        }

        @Test
        @DisplayName("only an ADVISOR escalates: a supervisor is who escalations go to, and raised_by has no SUPERVISOR")
        void onlyAdvisorEscalates() throws Exception {
            call(escalate(as(UserRole.SUPERVISOR)), 403);
            call(escalate(as(UserRole.ADMIN)), 403);
            call(escalate(as(karim)), 201);
        }

        @Test
        @DisplayName("an ADMIN administers but does not act on a customer's behalf")
        void adminDoesNotAct() throws Exception {
            call(ticket(as(UserRole.ADMIN)), 403);
            call(block(as(UserRole.ADMIN)), 403);
        }

        @Test
        @DisplayName("an ADMIN may read: the staff reads include administrators")
        void adminReads() throws Exception {
            call(get("/api/v1/customers/{id}/transactions", customer.getId()).with(as(UserRole.ADMIN)), 200);
            call(get("/api/v1/kb/articles").param("q", "carte").with(as(UserRole.ADMIN)), 200);
        }

        @Test
        @DisplayName("no write route answers an anonymous caller with anything but 401")
        void anonymousWritesAreUnauthenticated() throws Exception {
            call(ticket(anonymous()), 401);
            call(block(anonymous()), 401);
            call(escalate(anonymous()), 401);
        }

        @Test
        @DisplayName("a status in the ticket body is ignored: every ticket starts OPEN")
        void ticketStatusCannotBeSet() throws Exception {
            JsonNode created = call(post("/api/v1/tickets").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"customerId\": \"" + customer.getId()
                            + "\", \"category\": \"CARD\", \"title\": \"t\", \"status\": \"CLOSED\"}")
                    .with(as(UserRole.ADVISOR)), 201);
            assertThat(created.get("status").asText()).isEqualTo("OPEN");
        }
    }

    // ------------------------------------------------------------------ edge cases

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("a 404 for an unknown reference does not echo the reference back")
        void notFoundDoesNotEchoInput() throws Exception {
            JsonNode body = call(get("/api/v1/customers").param("externalRef", "<script>x</script>")
                    .with(as(UserRole.ADVISOR)), 404);
            assertThat(body.get("message").asText()).doesNotContain("script");
        }

        @Test
        @DisplayName("escalating an unknown conversation is 404")
        void unknownConversationIsNotFound() throws Exception {
            call(post("/api/v1/conversations/{id}/escalations", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"r\"}")
                    .with(as(UserRole.ADVISOR)), 404);
        }

        @Test
        @DisplayName("count=0 is refused, like count=51")
        void zeroCountIsRefused() throws Exception {
            Customer customer = fixtures.customer("EDGE-" + WorkspaceFixtures.suffix(), "Marseille");
            flushAndClear();
            call(get("/api/v1/customers/{id}/transactions", customer.getId())
                    .param("count", "0").with(as(UserRole.ADVISOR)), 400);
        }

        @Test
        @DisplayName("a cancelled card cannot be blocked either")
        void cancelledCardIsRefused() throws Exception {
            Customer customer = fixtures.customer("EDGE-" + WorkspaceFixtures.suffix(), "Marseille");
            Card card = fixtures.card(fixtures.account(customer, WorkspaceFixtures.iban()), CardStatus.CANCELLED);
            flushAndClear();
            call(post("/api/v1/cards/{id}/block", card.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\": \"LOST\"}").with(as(UserRole.ADVISOR)), 409);
        }
    }
}
