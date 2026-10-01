package com.callverse.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.SupervisionAlert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alert payload is a published contract, like openapi.yaml: the Next.js client parses it. Its
 * field set is pinned here, so a rename fails a test instead of a supervisor's screen.
 */
class SupervisionAlertContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static List<String> fields(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("every alert has the same versioned shape, with ids and last-four digits only")
    void shapeIsPinned() throws Exception {
        JsonNode escalation = mapper.valueToTree(SupervisionAlert.escalationRaised(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-01T16:52:00Z")));
        JsonNode block = mapper.valueToTree(SupervisionAlert.cardBlockedForFraud(
                UUID.randomUUID(), UUID.randomUUID(), "4242", Instant.parse("2026-10-01T16:53:00Z")));

        List<String> expected = List.of(
                "schemaVersion", "type", "occurredAt", "customerId", "conversationId", "escalationId",
                "cardId", "cardLast4");
        assertThat(fields(escalation)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(fields(block)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(escalation.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(escalation.get("type").asText()).isEqualTo("ESCALATION_RAISED");
        assertThat(block.get("type").asText()).isEqualTo("CARD_BLOCKED_FRAUD");
        assertThat(block.get("occurredAt").asText()).isEqualTo("2026-10-01T16:53:00Z");
    }
}
