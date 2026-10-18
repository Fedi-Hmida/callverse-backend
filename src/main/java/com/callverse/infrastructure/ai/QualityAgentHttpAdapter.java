package com.callverse.infrastructure.ai;

import com.callverse.core.application.exceptions.QualityServiceUnavailableException;
import com.callverse.core.application.interfaces.QualityAgent;
import com.callverse.core.application.interfaces.QualityAgent.QualityFlags;
import com.callverse.core.application.interfaces.QualityAgent.QualityMessage;
import com.callverse.core.application.interfaces.QualityAgent.QualityRequest;
import com.callverse.core.application.interfaces.QualityAgent.QualityResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP adapter for the Python Quality Analyst. */
@Component
class QualityAgentHttpAdapter implements QualityAgent {

    private final RestClient client;
    private final String apiKey;

    @Autowired
    QualityAgentHttpAdapter(
            RestClient.Builder builder,
            @Value("${ai.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.service.api-key:}") String apiKey,
            @Value("${ai.service.quality-read-timeout-seconds:30}") int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        if (readTimeoutSeconds < 1 || readTimeoutSeconds > 300) {
            throw new IllegalArgumentException("Quality read timeout must be between 1 and 300 seconds");
        }
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.apiKey = apiKey;
    }

    QualityAgentHttpAdapter(RestClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    public QualityResult evaluate(QualityRequest request) {
        QualityResponseWire response;
        try {
            response = client.post()
                    .uri("/ai/quality/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::addApiKey)
                    .body(toWire(request))
                    .retrieve()
                    .body(QualityResponseWire.class);
        } catch (RestClientException exception) {
            throw new QualityServiceUnavailableException("Quality service is unavailable.", exception);
        }
        return toResult(response);
    }

    private void addApiKey(HttpHeaders headers) {
        if (StringUtils.hasText(apiKey)) {
            headers.set("X-AI-Key", apiKey);
        }
    }

    private static QualityRequestWire toWire(QualityRequest request) {
        return new QualityRequestWire(
                request.conversationId().toString(),
                request.messages().stream().map(QualityAgentHttpAdapter::toWire).toList(),
                List.of(),
                request.resolved(),
                List.of());
    }

    private static QualityMessageWire toWire(QualityMessage message) {
        return new QualityMessageWire(message.sender().name(), message.content());
    }

    private static QualityResult toResult(QualityResponseWire response) {
        if (response == null
                || response.conversationId() == null
                || response.globalScore() == null
                || response.scores() == null
                || response.explanation() == null
                || response.flags() == null
                || response.flags().unsourcedClaims() == null
                || response.flags().unverifiableClaims() == null
                || response.flags().procedureViolations() == null
                || response.flags().missingSourceKeys() == null) {
            throw new QualityServiceUnavailableException("Quality service returned an invalid evaluation.");
        }
        try {
            return new QualityResult(
                    UUID.fromString(response.conversationId()),
                    BigDecimal.valueOf(response.globalScore()),
                    response.scores(),
                    response.explanation(),
                    new QualityFlags(
                            response.flags().unsourcedClaims(),
                            response.flags().unverifiableClaims(),
                            response.flags().procedureViolations(),
                            response.flags().missingSourceKeys()));
        } catch (IllegalArgumentException exception) {
            throw new QualityServiceUnavailableException("Quality service returned an invalid evaluation.", exception);
        }
    }

    private record QualityRequestWire(
            @JsonProperty("conversation_id") String conversationId,
            List<QualityMessageWire> messages,
            @JsonProperty("source_documents") List<SourceDocumentWire> sourceDocuments,
            boolean resolved,
            @JsonProperty("applicable_rules") List<String> applicableRules) {}

    private record QualityMessageWire(String sender, String content) {}

    private record SourceDocumentWire(
            @JsonProperty("doc_id") String documentId,
            @JsonProperty("chunk_id") String chunkId,
            String content) {}

    private record QualityResponseWire(
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("global_score") Double globalScore,
            Map<String, Integer> scores,
            String explanation,
            QualityFlagsWire flags) {}

    private record QualityFlagsWire(
            @JsonProperty("unsourced_claims") Integer unsourcedClaims,
            @JsonProperty("unverifiable_claims") Integer unverifiableClaims,
            @JsonProperty("procedure_violations") List<String> procedureViolations,
            @JsonProperty("missing_source_keys") List<String> missingSourceKeys) {}
}
