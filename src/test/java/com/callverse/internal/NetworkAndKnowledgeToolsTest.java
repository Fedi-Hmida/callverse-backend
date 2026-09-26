package com.callverse.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.core.domain.entities.KbArticle;
import com.callverse.core.domain.entities.NetworkIncident;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /internal/network/status} and {@code GET /internal/kb/search} — {@code OWNERSHIP_RULES.md}
 * E3 and E4.
 *
 * <p>E3 is the ESPRIT one: a simulation run injects fake outages, and the existing repository query
 * does not filter them, so without a mode predicate a simulated fibre cut would be announced to a
 * real customer as real. E4's traps are unpublished drafts and a search string that is really a
 * wildcard.
 */
class NetworkAndKnowledgeToolsTest extends AbstractInternalToolTest {

    private static final String ZONE = "MARSEILLE-8";

    // ---- network status ----------------------------------------------------------------------

    @Test
    @DisplayName("a live active incident in the zone is reported with its type, severity and estimated end")
    void activeIncidentIsReported() throws Exception {
        NetworkIncident live = fixtures.incident(ZONE, null, false);

        JsonNode body = ok(get("/internal/network/status").param("zone", ZONE));

        assertThat(body.get("zone").asText()).isEqualTo(ZONE);
        assertThat(body.get("activeIncident").asBoolean()).isTrue();
        JsonNode incident = body.get("incidents").get(0);
        assertThat(incident.get("id").asText()).isEqualTo(live.getId().toString());
        assertThat(incident.get("type").asText()).isEqualTo("FIBER_CUT");
        assertThat(incident.get("severity").asInt()).isEqualTo(4);
        assertThat(incident.get("estimatedEnd").asText()).isEqualTo("2026-09-26T13:00:00Z");
    }

    @Test
    @DisplayName("a simulated outage is never announced to a live customer")
    void simulatedIncidentIsInvisibleToLiveQueries() throws Exception {
        fixtures.incident(ZONE, UUID.randomUUID(), false);

        JsonNode body = ok(get("/internal/network/status").param("zone", ZONE));

        assertThat(body.get("activeIncident").asBoolean()).isFalse();
        assertThat(body.get("incidents")).isEmpty();
    }

    @Test
    @DisplayName("a simulation run sees its own incidents and nothing else — not live ones, not other runs'")
    void runScopedQuerySeesOnlyThatRun() throws Exception {
        UUID run = UUID.randomUUID();
        NetworkIncident mine = fixtures.incident(ZONE, run, false);
        fixtures.incident(ZONE, UUID.randomUUID(), false);
        fixtures.incident(ZONE, null, false);

        JsonNode body = ok(get("/internal/network/status").param("zone", ZONE).param("runId", run.toString()));

        assertThat(ids(body.get("incidents"))).containsExactly(mine.getId().toString());
    }

    @Test
    @DisplayName("resolved incidents and other zones are not reported")
    void resolvedAndOtherZonesAreIgnored() throws Exception {
        fixtures.incident(ZONE, null, true);
        fixtures.incident("LILLE-1", null, false);

        assertThat(ok(get("/internal/network/status").param("zone", ZONE)).get("activeIncident").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("the zone is required")
    void zoneIsRequired() throws Exception {
        ErrorEnvelope.assertConforms(call(get("/internal/network/status"), 400), 400, "VALIDATION_FAILED");
    }

    // ---- knowledge base ----------------------------------------------------------------------

    @Test
    @DisplayName("search is case-insensitive over title and content, published articles only")
    void searchFindsPublishedArticlesOnly() throws Exception {
        KbArticle byTitle = fixtures.article("INTERNET", "Box internet qui redemarre en boucle", "Verifier le cable.", true);
        KbArticle byContent = fixtures.article("INTERNET", "Debit faible", "Si la BOX clignote, redemarrer.", true);
        KbArticle draft = fixtures.article("INTERNET", "Box: brouillon interne", "Ne pas diffuser.", false);

        JsonNode body = ok(get("/internal/kb/search").param("q", "box"));

        assertThat(ids(body.get("articles")))
                .contains(byTitle.getId().toString(), byContent.getId().toString())
                .doesNotContain(draft.getId().toString());
        JsonNode first = body.get("articles").get(0);
        assertThat(first.has("content")).isTrue();
        assertThat(first.has("published")).as("drafts never appear, so the flag is noise").isFalse();
    }

    @Test
    @DisplayName("% and _ are searched literally: a wildcard does not return the whole knowledge base")
    void wildcardsAreLiteral() throws Exception {
        fixtures.article("BILLING", "Comprendre sa facture", "Les frais de mise en service.", true);
        KbArticle percent = fixtures.article("BILLING", "Remise de 20% sur le forfait", "Conditions.", true);

        JsonNode percentSearch = ok(get("/internal/kb/search").param("q", "20%"));
        JsonNode bareWildcards = ok(get("/internal/kb/search").param("q", "%_"));

        assertThat(ids(percentSearch.get("articles"))).containsExactly(percent.getId().toString());
        assertThat(bareWildcards.get("articles")).isEmpty();
    }

    @Test
    @DisplayName("k bounds the result count: at most k articles, and k above 10 is refused")
    void resultCountIsBounded() throws Exception {
        for (int i = 0; i < 4; i++) {
            fixtures.article("MOBILE", "Roaming guide " + i, "Activer le roaming.", true);
        }

        assertThat(ok(get("/internal/kb/search").param("q", "roaming").param("k", "2")).get("articles")).hasSize(2);
        ErrorEnvelope.assertConforms(
                call(get("/internal/kb/search").param("q", "roaming").param("k", "11"), 400), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a query shorter than 2 characters after trimming is refused, not turned into match-everything")
    void tooShortQueryIsRefused() throws Exception {
        ErrorEnvelope.assertConforms(call(get("/internal/kb/search").param("q", "  a "), 400), 400, "VALIDATION_FAILED");
    }
}
