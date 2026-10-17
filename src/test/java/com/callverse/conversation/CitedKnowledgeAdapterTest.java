package com.callverse.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.CitedKnowledge;
import com.callverse.core.application.interfaces.QualityAgent.SourceDocument;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.persistence.AbstractPersistenceTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** The articles the AI cited in a conversation, read from its private sources trace. */
class CitedKnowledgeAdapterTest extends AbstractPersistenceTest {

    @Autowired CitedKnowledge citedKnowledge;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private ConversationFixtures fx;
    private final Instant t0 = Instant.parse("2026-10-18T08:00:00Z");

    @BeforeEach
    void setUp() {
        fx = new ConversationFixtures(em, tx);
    }

    private UUID conversation() {
        return fx.conversation(fx.customer(), fx.skill(60), ConversationStatus.RESOLVED, "0", t0);
    }

    private static String sources(UUID... articleIds) {
        return "[" + String.join(",", java.util.Arrays.stream(articleIds)
                .map(id -> "{\"kb_article_id\":\"" + id + "\",\"score\":0.8}")
                .toList()) + "]";
    }

    @Test
    @DisplayName("each article cited by an AI turn comes back once, as title and content")
    void citedArticlesOnce() {
        UUID opposition = fx.kbArticle("Opposition carte", "Appeler le canal sécurisé.");
        UUID plafond = fx.kbArticle("Plafond de paiement", "Le plafond se modifie dans l'application.");
        UUID conversation = conversation();
        fx.aiAdvisorMessageWithSources(conversation, "Voici la procédure.", t0.plusSeconds(1), sources(opposition));
        fx.aiAdvisorMessageWithSources(conversation, "Et pour le plafond.", t0.plusSeconds(2), sources(opposition, plafond));

        List<SourceDocument> cited = citedKnowledge.citedInAiTurns(conversation);

        assertThat(cited).containsExactlyInAnyOrder(
                new SourceDocument(opposition.toString(), "article", "Opposition carte\nAppeler le canal sécurisé."),
                new SourceDocument(plafond.toString(), "article",
                        "Plafond de paiement\nLe plafond se modifie dans l'application."));
    }

    @Test
    @DisplayName("human messages, deleted articles and malformed traces are ignored")
    void ignoresWhatIsNotACitation() {
        UUID kept = fx.kbArticle("Opposition carte", "Appeler le canal sécurisé.");
        UUID conversation = conversation();
        fx.message(conversation, MessageSender.ADVISOR, "Réponse humaine", t0.plusSeconds(1));
        fx.aiAdvisorMessageWithSources(conversation, "a", t0.plusSeconds(2), sources(UUID.randomUUID(), kept));
        fx.aiAdvisorMessageWithSources(conversation, "b", t0.plusSeconds(3), "[{\"score\":0.5},{\"kb_article_id\":\"pas-un-uuid\"}]");
        fx.aiAdvisorMessageWithSources(conversation, "c", t0.plusSeconds(4), "{\"not\":\"an array\"}");

        assertThat(citedKnowledge.citedInAiTurns(conversation)).extracting(SourceDocument::documentId)
                .containsExactly(kept.toString());
        assertThat(citedKnowledge.citedInAiTurns(conversation())).isEmpty();
    }

    @Test
    @DisplayName("the judge's payload stays bounded: at most ten articles")
    void bounded() {
        UUID conversation = conversation();
        UUID[] articles = IntStream.range(0, 12)
                .mapToObj(i -> fx.kbArticle("Article " + i, "Contenu " + i))
                .toArray(UUID[]::new);
        fx.aiAdvisorMessageWithSources(conversation, "Beaucoup de sources.", t0.plusSeconds(1), sources(articles));

        assertThat(citedKnowledge.citedInAiTurns(conversation)).hasSize(10);
    }
}
