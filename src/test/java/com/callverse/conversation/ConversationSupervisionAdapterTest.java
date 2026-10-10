package com.callverse.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.ConversationSupervision;
import com.callverse.core.application.interfaces.ConversationSupervision.ConversationPage;
import com.callverse.core.application.interfaces.ConversationSupervision.ConversationSearch;
import com.callverse.core.application.interfaces.ConversationSupervision.ConversationSummary;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The supervisor's conversation list against a real PostgreSQL: every filter, the paging, the counts
 * and the rule that a simulation run never appears. Each test filters on its own fresh customer or
 * skill, so the rows of the rest of the suite never interfere.
 */
class ConversationSupervisionAdapterTest extends AbstractPersistenceTest {

    @Autowired ConversationSupervision supervision;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private ConversationFixtures fx;
    private final Instant t0 = Instant.parse("2026-10-10T07:00:00Z");

    @BeforeEach
    void setUp() {
        fx = new ConversationFixtures(em, tx);
    }

    private static ConversationSearch byCustomer(UUID customerId) {
        return new ConversationSearch(null, null, customerId, null, null, null, null);
    }

    @Test
    @DisplayName("every live conversation of a customer, newest first, with names, figures and message counts")
    void byCustomerWithDetails() {
        String skill = fx.skill(60);
        UUID amina = fx.namedCustomer("Amina", "Haddad");
        UUID karimUser = fx.user(UserRole.ADVISOR);
        UUID karim = fx.advisor(karimUser, 2, skill);
        UUID older = fx.conversation(amina, skill, ConversationStatus.RESOLVED, "0", t0, karim, null);
        UUID newer = fx.conversation(amina, skill, ConversationStatus.ESCALATED, "0", t0.plusSeconds(3600), karim, null);
        fx.metrics(older, 42, 360, true);
        fx.message(newer, MessageSender.CUSTOMER, "Ma carte est bloquee", t0.plusSeconds(3700));
        fx.message(newer, MessageSender.ADVISOR, "Je regarde", t0.plusSeconds(3720));
        fx.pendingEscalation(newer);

        ConversationPage page = supervision.search(byCustomer(amina), 0, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(ConversationSummary::id).containsExactly(newer, older);
        ConversationSummary first = page.content().get(0);
        assertThat(first.customerName()).isEqualTo("Amina Haddad");
        assertThat(first.customerReference()).isEqualTo(fx.reference(amina));
        assertThat(first.advisorId()).isEqualTo(karim);
        assertThat(first.advisorName()).startsWith("Karim");
        assertThat(first.skill()).isEqualTo(skill);
        assertThat(first.messageCount()).isEqualTo(2);
        assertThat(first.lastMessageAt()).isEqualTo(t0.plusSeconds(3720));
        assertThat(first.pendingEscalation()).isTrue();
        ConversationSummary second = page.content().get(1);
        assertThat(second.waitSeconds()).isEqualTo(42);
        assertThat(second.handleSeconds()).isEqualTo(360);
        assertThat(second.slaMet()).isTrue();
        assertThat(second.messageCount()).isZero();
        assertThat(second.lastMessageAt()).isNull();
        assertThat(second.pendingEscalation()).isFalse();
    }

    @Test
    @DisplayName("a simulation run's conversation never appears in the supervisor's list")
    void liveOnly() {
        String skill = fx.skill(60);
        UUID customer = fx.customer();
        UUID live = fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", t0);
        fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", t0, null, UUID.randomUUID());

        assertThat(supervision.search(byCustomer(customer), 0, 20).content())
                .extracting(ConversationSummary::id).containsExactly(live);
    }

    @Test
    @DisplayName("filters combine: several statuses, a skill, an advisor, a date window")
    void filters() {
        String skill = fx.skill(60);
        String otherSkill = fx.skill(60);
        UUID customer = fx.customer();
        UUID karim = fx.advisor(fx.user(UserRole.ADVISOR), 3, skill, otherSkill);
        UUID lina = fx.advisor(fx.user(UserRole.ADVISOR), 3, skill);
        UUID queued = fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", t0);
        UUID active = fx.conversation(customer, skill, ConversationStatus.ACTIVE, "0", t0.plusSeconds(60), karim, null);
        UUID escalated = fx.conversation(customer, skill, ConversationStatus.ESCALATED, "0", t0.plusSeconds(120), lina, null);
        UUID elsewhere = fx.conversation(customer, otherSkill, ConversationStatus.ACTIVE, "0", t0.plusSeconds(180), karim, null);

        assertThat(ids(new ConversationSearch(Set.of(ConversationStatus.ACTIVE, ConversationStatus.ESCALATED),
                        null, customer, null, null, null, null)))
                .containsExactly(elsewhere, escalated, active);
        assertThat(ids(new ConversationSearch(null, otherSkill, customer, null, null, null, null)))
                .containsExactly(elsewhere);
        assertThat(ids(new ConversationSearch(null, null, customer, karim, null, null, null)))
                .containsExactly(elsewhere, active);
        assertThat(ids(new ConversationSearch(null, null, customer, null, null, t0.plusSeconds(60), t0.plusSeconds(180))))
                .as("from is inclusive, to is exclusive").containsExactly(escalated, active);
        assertThat(ids(new ConversationSearch(Set.of(ConversationStatus.QUEUED), null, customer, null, null, null, null)))
                .containsExactly(queued);
    }

    @Test
    @DisplayName("the text search finds a customer by name or reference, case-insensitively; % is a literal")
    void textSearch() {
        String skill = fx.skill(60);
        String tag = "Zq" + UUID.randomUUID().toString().substring(0, 6);
        UUID sofia = fx.namedCustomer("Sofia", tag);
        UUID c = fx.conversation(sofia, skill, ConversationStatus.QUEUED, "0", t0);

        assertThat(ids(new ConversationSearch(null, skill, null, null, tag.toUpperCase(), null, null))).containsExactly(c);
        assertThat(ids(new ConversationSearch(null, skill, null, null, fx.reference(sofia).toLowerCase(), null, null)))
                .containsExactly(c);
        assertThat(ids(new ConversationSearch(null, skill, null, null, "sofia " + tag, null, null)))
                .as("first and last name together").containsExactly(c);
        assertThat(ids(new ConversationSearch(null, skill, null, null, "%", null, null))).isEmpty();
        assertThat(ids(new ConversationSearch(null, skill, null, null, "_", null, null)))
                .as("_ is a literal too, not a one-character wildcard").isEmpty();
    }

    @Test
    @DisplayName("pages are counted exactly and do not overlap")
    void paging() {
        String skill = fx.skill(60);
        UUID customer = fx.customer();
        for (int i = 0; i < 5; i++) {
            fx.conversation(customer, skill, ConversationStatus.QUEUED, "0", t0.plusSeconds(i));
        }

        ConversationPage first = supervision.search(byCustomer(customer), 0, 2);
        ConversationPage last = supervision.search(byCustomer(customer), 2, 2);

        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.content()).hasSize(2);
        assertThat(last.content()).hasSize(1);
        assertThat(last.content().get(0).id()).isNotIn(first.content().stream().map(ConversationSummary::id).toList());
    }

    private java.util.List<UUID> ids(ConversationSearch search) {
        return supervision.search(search, 0, 50).content().stream().map(ConversationSummary::id).toList();
    }
}
