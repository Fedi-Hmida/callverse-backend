package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.ConversationAiExplanations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ConversationAiExplanationsAdapter implements ConversationAiExplanations {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    @Override
    public Optional<Explanation> find(UUID conversationId, long messageId) {
        return jdbc.query("""
                select m.sources::text sources, m.tool_calls::text calls, d.action::text decision
                  from message m
                  left join lateral (
                    select action from agent_decision
                     where agent_type='CUSTOMER_ADVISOR'
                       and observation->>'conversationId'=:conversationText
                       and observation->>'messageId'=:messageText
                     order by created_at desc,id desc limit 1
                  ) d on true
                 where m.conversation_id=:conversationId and m.id=:messageId and m.ai_generated=true
                """, Map.of("conversationId", conversationId, "messageId", messageId,
                        "conversationText", conversationId.toString(), "messageText", Long.toString(messageId)),
                (rs, row) -> {
                    JsonNode decision = parse(rs.getString("decision"));
                    List<SourceReference> sources = new ArrayList<>();
                    for (JsonNode source : parse(rs.getString("sources"))) {
                        UUID id;
                        try { id = UUID.fromString(source.path("kb_article_id").asText()); }
                        catch (IllegalArgumentException exception) { continue; }
                        List<String> titles = jdbc.query("select title from kb_article where id=:id", Map.of("id", id),
                                (record, index) -> record.getString("title"));
                        sources.add(new SourceReference(id, titles.isEmpty() ? "Document indisponible" : titles.getFirst(),
                                source.path("score").isNumber() ? source.path("score").asDouble() : null));
                    }
                    List<ToolCheck> checks = new ArrayList<>();
                    for (JsonNode call : parse(rs.getString("calls"))) {
                        checks.add(new ToolCheck(call.path("tool").asText("Vérification"), call.path("ok").asBoolean(false)));
                    }
                    boolean recorded = decision.hasNonNull("confidence");
                    return new Explanation(messageId, recorded,
                            decision.path("intent").asText("OTHER"),
                            recorded ? decision.path("confidence").asDouble() : null,
                            decision.path("type").asText("UNKNOWN"),
                            decision.path("reason").asText("La décision d’origine n’a pas été enregistrée pour cet ancien message. Les références disponibles sont affichées ci-dessous."),
                            decision.path("latencyMs").isNumber() ? decision.path("latencyMs").asLong() : null,
                            List.copyOf(sources), List.copyOf(checks));
                }).stream().findFirst();
    }

    private JsonNode parse(String value) {
        try { return value == null ? json.createObjectNode() : json.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return json.createObjectNode(); }
    }
}
