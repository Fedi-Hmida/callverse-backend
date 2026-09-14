package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.MessageSender;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One turn in a conversation transcript. Maps {@code message}.
 *
 * <p><strong>{@code BIGSERIAL}, not UUID.</strong> This is the highest-volume table in the schema,
 * and a sequential 8-byte integer is both more compact and better clustered in the index than a
 * random 16-byte UUID. The trade-off — a message id is guessable and enumerable — is acceptable
 * precisely because message ids are never exposed publicly; the conversation's UUID is.
 *
 * <p>{@code sources} and {@code tool_calls} are JSONB because their shape varies with the agent and
 * the tools it invoked, and because nothing ever joins on them: they are written once and read
 * whole, which is the case JSONB is for. They map to {@link JsonNode} rather than to a POJO or a
 * {@code Map}: a Map cannot represent the top-level arrays these columns actually hold, and a POJO
 * per shape would need a migration every time an agent gains a tool.
 */
@Entity
@Table(name = "message")
@Getter
@Setter
@NoArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender", nullable = false, length = 20)
    private MessageSender sender;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** Distinguishes an agent's turn from a human advisor's. Feeds the quality evaluation. */
    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated = false;

    /** Knowledge-base articles the RAG retrieved for this turn. Used to detect unsourced claims. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources")
    private JsonNode sources;

    /** Tools the agent invoked producing this turn, with their arguments. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls")
    private JsonNode toolCalls;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt = Instant.now();
}
