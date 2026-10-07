package com.callverse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reduces an error body to its shape: field names in order, each with its JSON type.
 *
 * <p>The envelope has two writers — {@code GlobalExceptionHandler} inside MVC, and the security
 * chain's entry point and denied handler before it. Comparing shapes rather than eyeballing them is
 * how a test proves the two cannot drift: a renamed field, a reordered one, a status serialised as a
 * string or a timestamp serialised as epoch millis all change the shape.
 */
public final class ErrorEnvelope {

    /** ISO-8601 UTC with a {@code Z}, as Jackson writes an {@link java.time.Instant} here. */
    private static final String ISO_UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    private ErrorEnvelope() {}

    public static List<String> shapeOf(JsonNode body) {
        List<String> shape = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : body.properties()) {
            shape.add(field.getKey() + ":" + field.getValue().getNodeType());
        }
        return shape;
    }

    /** The frozen contract: exactly these five fields, in this order, with these types. */
    public static void assertConforms(JsonNode body, int status, String code) {
        assertThat(shapeOf(body))
                .containsExactly(
                        "timestamp:STRING", "status:NUMBER", "code:STRING", "message:STRING", "path:STRING");
        assertThat(body.get("timestamp").asText()).matches(ISO_UTC);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
    }
}
