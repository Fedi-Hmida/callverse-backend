package com.callverse.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.QualityEvaluationStore;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.persistence.AbstractPersistenceTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** The backlog of resolved live conversations still waiting for an AI grade. */
class UngradedConversationsAdapterTest extends AbstractPersistenceTest {

    @Autowired QualityEvaluationStore store;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private ConversationFixtures fx;
    private final Instant now = Instant.parse("2026-10-18T10:00:00Z");
    private final Instant after = now.minus(24, ChronoUnit.HOURS);
    private final Instant before = now.minus(5, ChronoUnit.MINUTES);

    @BeforeEach
    void setUp() {
        fx = new ConversationFixtures(em, tx);
    }

    private UUID ended(ConversationStatus status, Instant endedAt, UUID runId) {
        UUID id = fx.conversation(fx.customer(), fx.skill(60), status, "0", endedAt.minusSeconds(600), null, runId);
        fx.ended(id, endedAt);
        return id;
    }

    @Test
    @DisplayName("resolved, live, in the window and without an AI grade: due for a retry")
    void dueConversations() {
        UUID due = ended(ConversationStatus.RESOLVED, now.minus(1, ChronoUnit.HOURS), null);
        UUID humanGradedOnly = ended(ConversationStatus.RESOLVED, now.minus(2, ChronoUnit.HOURS), null);
        fx.qualityEvaluation(humanGradedOnly, "HUMAN");
        UUID aiGraded = ended(ConversationStatus.RESOLVED, now.minus(3, ChronoUnit.HOURS), null);
        fx.qualityEvaluation(aiGraded, "AI");
        UUID tooRecent = ended(ConversationStatus.RESOLVED, now.minus(1, ChronoUnit.MINUTES), null);
        UUID tooOld = ended(ConversationStatus.RESOLVED, now.minus(30, ChronoUnit.HOURS), null);
        UUID abandoned = ended(ConversationStatus.ABANDONED, now.minus(1, ChronoUnit.HOURS), null);

        var found = store.ungradedResolved(after, before, 500);

        assertThat(found).contains(due, humanGradedOnly)
                .doesNotContain(aiGraded, tooRecent, tooOld, abandoned);
    }

    @Test
    @DisplayName("a simulated conversation is never retried, and the batch is bounded")
    void liveOnlyAndBounded() {
        UUID simulated = ended(ConversationStatus.RESOLVED, now.minus(1, ChronoUnit.HOURS), UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            ended(ConversationStatus.RESOLVED, now.minus(1, ChronoUnit.HOURS), null);
        }

        assertThat(store.ungradedResolved(after, before, 500)).doesNotContain(simulated);
        assertThat(store.ungradedResolved(after, before, 2)).hasSize(2);
    }
}
