package com.callverse.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Escalation;
import com.callverse.core.domain.entities.Ticket;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
import com.callverse.core.domain.enums.TicketStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /internal/tickets} and {@code POST /internal/conversations/{id}/escalate} —
 * {@code OWNERSHIP_RULES.md} E5 and E7. The two tools that write.
 *
 * <p>E5: the agent names a customer, and the backend checks that name against the conversation it
 * is handling — otherwise an agent (or whoever holds the key) could file tickets on anyone. Status is
 * the backend's decision, and out-of-range severity is a 400, not a 500 from the CHECK constraint.
 *
 * <p>E7: an escalation is raised by the AI and nobody else, only from a state the conversation
 * state machine allows, and only once — a retrying agent must not queue ten escalations for one
 * supervisor.
 */
class TicketAndEscalationToolsTest extends AbstractInternalToolTest {

    // ---- tickets ------------------------------------------------------------------------------

    @Test
    @DisplayName("a ticket is created OPEN with default severity 3, whatever status the body asks for")
    void ticketIsCreatedOpen() throws Exception {
        Customer customer = fixtures.customer("NANTES-2", false);
        Conversation conversation = fixtures.conversation(customer, ConversationStatus.ACTIVE);

        JsonNode body =
                call(tickets(ticket(customer.getId(), conversation.getId()).put("status", "CLOSED")), 201);

        assertThat(body.get("status").asText()).isEqualTo("OPEN");
        assertThat(body.get("severity").asInt()).isEqualTo(3);
        assertThat(body.get("customerId").asText()).isEqualTo(customer.getId().toString());
        assertThat(body.get("conversationId").asText()).isEqualTo(conversation.getId().toString());
        Ticket stored = em.find(Ticket.class, UUID.fromString(body.get("id").asText()));
        assertThat(stored.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(stored.getCustomer().getId()).isEqualTo(customer.getId());
    }

    @Test
    @DisplayName("a ticket without a conversation is allowed, for a known customer")
    void ticketWithoutConversation() throws Exception {
        Customer customer = fixtures.customer("NANTES-2", false);

        JsonNode body = call(tickets(ticket(customer.getId(), null).put("severity", 5)), 201);

        assertThat(body.get("conversationId").isNull()).isTrue();
        assertThat(body.get("severity").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("a ticket naming one customer on another customer's conversation is refused")
    void conversationOfAnotherCustomerIsRefused() throws Exception {
        Customer claimed = fixtures.customer("NANTES-2", false);
        Customer owner = fixtures.customer("NANTES-2", false);
        Conversation theirs = fixtures.conversation(owner, ConversationStatus.ACTIVE);

        ErrorEnvelope.assertConforms(
                call(tickets(ticket(claimed.getId(), theirs.getId())), 400), 400, "CONVERSATION_CUSTOMER_MISMATCH");
        assertThat(em.createQuery("select count(t) from Ticket t", Long.class).getSingleResult())
                .as("nothing written")
                .isZero();
    }

    @Test
    @DisplayName("an unknown customer or conversation is 404")
    void unknownReferencesAreNotFound() throws Exception {
        Customer customer = fixtures.customer("NANTES-2", false);

        ErrorEnvelope.assertConforms(call(tickets(ticket(UUID.randomUUID(), null)), 404), 404, "RESOURCE_NOT_FOUND");
        ErrorEnvelope.assertConforms(
                call(tickets(ticket(customer.getId(), UUID.randomUUID())), 404), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("severity outside 1..5 and a blank title are 400 VALIDATION_FAILED, never a 500 from the CHECK")
    void invalidFieldsAreRejectedAtTheEdge() throws Exception {
        Customer customer = fixtures.customer("NANTES-2", false);

        ErrorEnvelope.assertConforms(
                call(tickets(ticket(customer.getId(), null).put("severity", 6)), 400), 400, "VALIDATION_FAILED");
        ErrorEnvelope.assertConforms(
                call(tickets(ticket(customer.getId(), null).put("title", " ")), 400), 400, "VALIDATION_FAILED");
    }

    // ---- escalation ---------------------------------------------------------------------------

    @Test
    @DisplayName("escalating an ACTIVE conversation creates one PENDING escalation raised by AI")
    void escalationIsCreated() throws Exception {
        Conversation conversation = fixtures.conversation(fixtures.customer("TOULOUSE-1", false), ConversationStatus.ACTIVE);

        JsonNode body = call(escalate(conversation.getId(), "Customer asks for a supervisor"), 201);

        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("raisedBy").asText()).isEqualTo("AI");
        Escalation stored = em.find(Escalation.class, UUID.fromString(body.get("id").asText()));
        assertThat(stored.getRaisedBy()).isEqualTo(EscalationRaisedBy.AI);
        assertThat(stored.getStatus()).isEqualTo(EscalationStatus.PENDING);
    }

    @Test
    @DisplayName("a second escalation of the same conversation returns the pending one instead of adding another")
    void escalationIsIdempotent() throws Exception {
        Conversation conversation = fixtures.conversation(fixtures.customer("TOULOUSE-1", false), ConversationStatus.ACTIVE);

        JsonNode first = call(escalate(conversation.getId(), "First attempt"), 201);
        JsonNode second = call(escalate(conversation.getId(), "Agent retried"), 200);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("reason").asText()).isEqualTo("First attempt");
        List<Escalation> rows =
                em.createQuery("select e from Escalation e where e.conversation.id = :id", Escalation.class)
                        .setParameter("id", conversation.getId())
                        .getResultList();
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("the escalation tool does not move the conversation's status: that is the Phase 4 state machine")
    void conversationStatusIsUntouched() throws Exception {
        Conversation conversation = fixtures.conversation(fixtures.customer("TOULOUSE-1", false), ConversationStatus.ACTIVE);

        call(escalate(conversation.getId(), "Needs a human"), 201);
        em.refresh(conversation);

        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    @DisplayName("a resolved or abandoned conversation cannot be escalated: 409 INVALID_STATE_TRANSITION")
    void terminalConversationCannotBeEscalated() throws Exception {
        Customer customer = fixtures.customer("TOULOUSE-1", false);
        Conversation resolved = fixtures.conversation(customer, ConversationStatus.RESOLVED);
        Conversation abandoned = fixtures.conversation(customer, ConversationStatus.ABANDONED);

        ErrorEnvelope.assertConforms(call(escalate(resolved.getId(), "Too late"), 409), 409, "INVALID_STATE_TRANSITION");
        ErrorEnvelope.assertConforms(call(escalate(abandoned.getId(), "Too late"), 409), 409, "INVALID_STATE_TRANSITION");
    }

    @Test
    @DisplayName("an unknown conversation is 404 and a blank reason is 400")
    void unknownConversationAndBlankReason() throws Exception {
        Conversation conversation = fixtures.conversation(fixtures.customer("TOULOUSE-1", false), ConversationStatus.ACTIVE);

        ErrorEnvelope.assertConforms(call(escalate(UUID.randomUUID(), "Why"), 404), 404, "RESOURCE_NOT_FOUND");
        ErrorEnvelope.assertConforms(call(escalate(conversation.getId(), "  "), 400), 400, "VALIDATION_FAILED");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ObjectNode ticket(UUID customerId, UUID conversationId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("customerId", customerId.toString());
        if (conversationId != null) {
            body.put("conversationId", conversationId.toString());
        }
        body.put("category", "BILLING");
        body.put("title", "Double charge on the September invoice");
        body.put("description", "Customer reports two debits of 52.40 EUR.");
        return body;
    }

    private MockHttpServletRequestBuilder tickets(ObjectNode body) {
        return post("/internal/tickets").contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }

    private MockHttpServletRequestBuilder escalate(UUID conversationId, String reason) {
        return post("/internal/conversations/" + conversationId + "/escalate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("reason", reason).toString());
    }
}
