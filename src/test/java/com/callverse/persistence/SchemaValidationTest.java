package com.callverse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.domain.entities.AgentDecision;
import com.callverse.core.domain.entities.ControlStrategy;
import com.callverse.core.domain.entities.KbArticle;
import com.callverse.core.domain.entities.KbChunk;
import com.callverse.core.domain.entities.MetricSample;
import com.callverse.core.domain.entities.MetricSampleId;
import com.callverse.core.domain.entities.ServiceIncident;
import com.callverse.core.domain.enums.BankingService;
import com.callverse.core.domain.entities.QualityEvaluation;
import com.callverse.core.domain.entities.RoutingRule;
import com.callverse.core.domain.entities.RunKpi;
import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.entities.SimulationRun;
import com.callverse.core.domain.enums.AgentType;
import com.callverse.core.domain.enums.EvaluatorType;
import com.callverse.core.domain.enums.LoadProfile;
import com.callverse.core.domain.enums.RunStatus;
import com.callverse.core.domain.enums.StrategyKind;
import java.time.Instant;
import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.entities.AdvisorSkill;
import com.callverse.core.domain.entities.AdvisorSkillId;
import com.callverse.core.domain.entities.CommercialCredit;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Escalation;
import com.callverse.core.domain.entities.Message;
import com.callverse.core.domain.entities.Skill;
import com.callverse.core.domain.entities.Ticket;
import com.callverse.core.domain.enums.AdvisorStatus;
import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.TicketStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import com.callverse.core.domain.entities.AppUser;
import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.BankTransaction;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.CardBlockReason;
import com.callverse.core.domain.enums.CardNetwork;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.CardType;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
import com.callverse.core.domain.enums.UserRole;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves that the migrations ({@code V1} to {@code V3}) and the JPA entities agree, against a real
 * PostgreSQL.
 *
 * <p><strong>What makes this test meaningful.</strong> The application runs with
 * {@code ddl-auto: validate}, so Hibernate compares every mapped column against the live schema at
 * startup. Until entities existed, that check passed by comparing nothing — a green light that
 * proved only that there was nothing to check. Every test here depends on the Spring context having
 * started, which means Flyway applied every migration and validation passed against real tables.
 *
 * <p>Validation is directional and it is worth knowing which way: Hibernate checks that everything
 * the entities map exists in the database. It does <em>not</em> check the reverse, so a column
 * present in the migration and absent from the entity — {@code kb_chunk.embedding}, deliberately —
 * is invisible to it.
 */
@Transactional
class SchemaValidationTest extends AbstractPersistenceTest {

    @Autowired EntityManager em;
    @Autowired ObjectMapper objectMapper;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("the context starts, so Flyway ran and ddl-auto=validate accepted every entity")
    void schemaValidates() {
        // Reaching this line is the assertion. Kept explicit so a failure reads as a schema
        // disagreement rather than as an unexplained context-loading error.
        assertThat(em).isNotNull();
    }

    @Nested
    @DisplayName("Block 1-2 — identity and customer domain")
    class IdentityAndCustomer {

        @Test
        @DisplayName("a customer, an account, a card and a card payment round-trip")
        void customerAggregateRoundTrips() {
            AppUser user = new AppUser();
            user.setEmail(unique("round.trip") + "@callverse.local");
            user.setPasswordHash("$2a$10$notarealhashjustfortestingpurposesonly000000000000000000");
            user.setFirstName("Round");
            user.setLastName("Trip");
            user.setRole(UserRole.CUSTOMER);
            em.persist(user);

            Customer customer = new Customer();
            customer.setUser(user);
            customer.setExternalRef(unique("CUST"));
            customer.setFirstName("Round");
            customer.setLastName("Trip");
            customer.setRegion("north");
            customer.setSegment(CustomerSegment.PRIVATE);
            customer.setChurnRisk(ChurnRisk.HIGH);
            customer.setTenureMonths(18);
            em.persist(customer);

            BankingProduct product = em.createQuery(
                            "select p from BankingProduct p where p.code = :c", BankingProduct.class)
                    .setParameter("c", "CUR_PREMIUM")
                    .getSingleResult();

            Account account = new Account();
            account.setCustomer(customer);
            account.setProduct(product);
            account.setIban("FR76" + unique("ACC").replace("-", "").toUpperCase());
            account.setBalance(new BigDecimal("-120.50"));
            account.setOverdraftLimit(new BigDecimal("500.00"));
            account.setStatus(AccountStatus.ACTIVE);
            account.setOpenedAt(LocalDate.of(2025, 1, 15));
            em.persist(account);

            Card card = new Card();
            card.setAccount(account);
            card.setPanLast4("4242");
            card.setNetwork(CardNetwork.VISA);
            card.setType(CardType.DEBIT);
            card.setStatus(CardStatus.BLOCKED);
            card.setBlockedAt(Instant.now());
            card.setBlockReason(CardBlockReason.FRAUD_SUSPECTED);
            card.setExpiresOn(LocalDate.of(2029, 12, 31));
            card.setDailyLimit(new BigDecimal("1000.00"));
            em.persist(card);

            BankTransaction payment = new BankTransaction();
            payment.setAccount(account);
            payment.setCard(card);
            payment.setType(TransactionType.CARD_PAYMENT);
            payment.setAmount(new BigDecimal("-89.90"));
            payment.setLabel("CB MARKET 29/09");
            payment.setStatus(TransactionStatus.DISPUTED);
            em.persist(payment);

            em.flush();
            em.clear();

            // Traverse the whole chain back: transaction -> card/account -> customer -> user, plus
            // the aggregate's own collection in the other direction.
            BankTransaction loaded = em.createQuery(
                            "select t from BankTransaction t where t.id = :id", BankTransaction.class)
                    .setParameter("id", payment.getId())
                    .getSingleResult();
            assertThat(loaded.getAmount()).isEqualByComparingTo("-89.90");
            assertThat(loaded.getStatus()).isEqualTo(TransactionStatus.DISPUTED);
            assertThat(loaded.getCard().getPanLast4()).isEqualTo("4242");
            assertThat(loaded.getCard().getBlockReason()).isEqualTo(CardBlockReason.FRAUD_SUSPECTED);
            assertThat(loaded.getAccount().getProduct().getCode()).isEqualTo("CUR_PREMIUM");
            assertThat(loaded.getAccount().getBalance()).isEqualByComparingTo("-120.50");
            assertThat(loaded.getAccount().getCustomer().getSegment()).isEqualTo(CustomerSegment.PRIVATE);
            assertThat(loaded.getAccount().getCustomer().getChurnRisk()).isEqualTo(ChurnRisk.HIGH);
            assertThat(loaded.getAccount().getCustomer().getUser().getRole())
                    .isEqualTo(UserRole.CUSTOMER);
            assertThat(loaded.getAccount().getCustomer().getAccounts())
                    .extracting(Account::getId)
                    .contains(account.getId());
        }

        @Test
        @DisplayName("a simulated customer needs no account, which is what lets both modes coexist")
        void simulatedCustomerHasNoUser() {
            Customer simulated = new Customer();
            simulated.setExternalRef(unique("SIM"));
            simulated.setFirstName("Synthetic");
            simulated.setLastName("Customer");
            simulated.setRegion("south");
            simulated.setSimulated(true);
            em.persist(simulated);
            em.flush();
            em.clear();

            Customer loaded = em.find(Customer.class, simulated.getId());
            assertThat(loaded.getUser()).isNull();
            assertThat(loaded.isSimulated()).isTrue();
            assertThat(loaded.getChurnRisk()).isEqualTo(ChurnRisk.LOW);
            assertThat(loaded.getSegment()).isEqualTo(CustomerSegment.MASS);
        }

        @Test
        @DisplayName("BigDecimal money keeps its scale through a round trip")
        void moneyKeepsScale() {
            BankingProduct product = em.createQuery(
                            "select p from BankingProduct p where p.code = :c", BankingProduct.class)
                    .setParameter("c", "CUR_PREMIUM")
                    .getSingleResult();
            // NUMERIC(8,2): the value must come back as stored, not as a binary-float approximation.
            assertThat(product.getMonthlyFee()).isEqualByComparingTo("9.90");
            assertThat(product.getMonthlyFee().scale()).isEqualTo(2);
            BankingProduct savings = em.createQuery(
                            "select p from BankingProduct p where p.code = :c", BankingProduct.class)
                    .setParameter("c", "SAV_LIVRET")
                    .getSingleResult();
            // NUMERIC(6,4): a rate is a fraction with four decimals, never a rounded percentage.
            assertThat(savings.getInterestRate()).isEqualByComparingTo("0.0300");
            assertThat(savings.getInterestRate().scale()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Block 3-4 — center resources and the interaction core")
    class ResourcesAndInteraction {

        private Skill skill(String code) {
            return em.createQuery("select s from Skill s where s.code = :c", Skill.class)
                    .setParameter("c", code)
                    .getSingleResult();
        }

        private Customer persistCustomer() {
            Customer c = new Customer();
            c.setExternalRef(unique("CUST"));
            c.setFirstName("Block");
            c.setLastName("Four");
            c.setRegion("east");
            em.persist(c);
            return c;
        }

        @Test
        @DisplayName("advisor_skill is an entity with a composite key, carrying its level payload")
        void advisorSkillCompositeKeyRoundTrips() {
            Advisor advisor = new Advisor();
            advisor.setDisplayName("Test Advisor");
            advisor.setStatus(AdvisorStatus.AVAILABLE);
            advisor.setMaxConcurrent(3);
            advisor.setSimulated(true);
            em.persist(advisor);

            Skill cards = skill("CARDS");
            AdvisorSkill link = new AdvisorSkill();
            link.setAdvisor(advisor);
            link.setSkill(cards);
            link.setLevel((short) 3);
            em.persist(link);

            em.flush();
            em.clear();

            // The payload is what forces this to be an entity rather than a @ManyToMany.
            AdvisorSkill loaded = em.find(
                    AdvisorSkill.class, new AdvisorSkillId(advisor.getId(), cards.getId()));
            assertThat(loaded.getLevel()).isEqualTo((short) 3);
            assertThat(loaded.getSkill().getCode()).isEqualTo("CARDS");

            // And the routing engine's direction: advisor -> skills.
            Advisor reloaded = em.find(Advisor.class, advisor.getId());
            assertThat(reloaded.getSkills()).hasSize(1);
            assertThat(reloaded.getCreditLimit()).isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("a conversation with messages round-trips, including its JSONB columns")
        void conversationWithMessagesRoundTrips() throws Exception {
            Conversation conversation = new Conversation();
            conversation.setCustomer(persistCustomer());
            conversation.setSkill(skill("ACCOUNTS"));
            conversation.setStatus(ConversationStatus.ACTIVE);
            conversation.setIntent(Intent.BALANCE);
            conversation.setPriorityScore(new BigDecimal("42.50"));
            em.persist(conversation);

            Message customerTurn = new Message();
            customerTurn.setConversation(conversation);
            customerTurn.setSender(MessageSender.CUSTOMER);
            customerTurn.setContent("Mon solde est incorrect.");
            em.persist(customerTurn);

            Message agentTurn = new Message();
            agentTurn.setConversation(conversation);
            agentTurn.setSender(MessageSender.ADVISOR);
            agentTurn.setContent("Je regarde cela tout de suite.");
            agentTurn.setAiGenerated(true);
            // Top-level arrays: the shape a Map<String,Object> mapping could not have held.
            agentTurn.setSources(objectMapper.readTree("[{\"article\":\"KB-114\",\"score\":0.91}]"));
            agentTurn.setToolCalls(objectMapper.readTree(
                    "[{\"tool\":\"get_transactions\",\"args\":{\"n\":10}}]"));
            em.persist(agentTurn);

            em.flush();
            em.clear();

            Conversation loaded = em.find(Conversation.class, conversation.getId());
            assertThat(loaded.getRunId()).as("live mode conversation").isNull();
            assertThat(loaded.getChannel()).isEqualTo(Channel.CHAT);
            assertThat(loaded.getPriorityScore()).isEqualByComparingTo("42.50");
            assertThat(loaded.getSkill().getCode()).isEqualTo("ACCOUNTS");

            // No collection on Conversation by design; messages are queried, not traversed.
            List<Message> transcript = em.createQuery(
                            "select m from Message m where m.conversation.id = :id order by m.sentAt",
                            Message.class)
                    .setParameter("id", conversation.getId())
                    .getResultList();
            assertThat(transcript).hasSize(2);
            assertThat(transcript.get(1).getSources().get(0).get("article").asText())
                    .isEqualTo("KB-114");
            assertThat(transcript.get(1).getToolCalls().get(0).get("tool").asText())
                    .isEqualTo("get_transactions");
            assertThat(transcript.get(1).getId()).isNotNull(); // BIGSERIAL identity assigned
        }

        @Test
        @DisplayName("a commercial credit records both who granted it and who approved the excess")
        void commercialCreditRecordsApprovalChain() {
            Customer customer = persistCustomer();

            Advisor advisor = new Advisor();
            advisor.setDisplayName("Granting Advisor");
            em.persist(advisor);

            AppUser supervisor = new AppUser();
            supervisor.setEmail(unique("sup") + "@callverse.local");
            supervisor.setPasswordHash("$2a$10$notarealhashjustfortestingpurposesonly000000000000000000");
            supervisor.setFirstName("Super");
            supervisor.setLastName("Visor");
            supervisor.setRole(UserRole.SUPERVISOR);
            em.persist(supervisor);

            CommercialCredit credit = new CommercialCredit();
            credit.setCustomer(customer);
            credit.setAmount(new BigDecimal("25.00")); // above the 15.00 default ceiling
            credit.setReason("Remboursement frais de rejet");
            credit.setGrantedBy(advisor);
            credit.setApprovedBy(supervisor);
            em.persist(credit);

            em.flush();
            em.clear();

            CommercialCredit loaded = em.find(CommercialCredit.class, credit.getId());
            // A non-null approvedBy is exactly the audit signal: this exceeded a ceiling.
            assertThat(loaded.getAmount()).isEqualByComparingTo("25.00");
            assertThat(loaded.getAmount()).isGreaterThan(loaded.getGrantedBy().getCreditLimit());
            assertThat(loaded.getApprovedBy().getRole()).isEqualTo(UserRole.SUPERVISOR);
        }

        @Test
        @DisplayName("an escalation round-trips and defaults to PENDING")
        void escalationRoundTrips() {
            Conversation conversation = new Conversation();
            conversation.setCustomer(persistCustomer());
            conversation.setStatus(ConversationStatus.ESCALATED);
            em.persist(conversation);

            Escalation escalation = new Escalation();
            escalation.setConversation(conversation);
            escalation.setReason("Agent hors perimetre");
            escalation.setRaisedBy(EscalationRaisedBy.AI);
            em.persist(escalation);

            em.flush();
            em.clear();

            Escalation loaded = em.find(Escalation.class, escalation.getId());
            assertThat(loaded.getStatus()).isEqualTo(EscalationStatus.PENDING);
            assertThat(loaded.getRaisedBy()).isEqualTo(EscalationRaisedBy.AI);
            assertThat(loaded.getResolvedBy()).isNull();
        }

        @Test
        @DisplayName("a ticket survives its conversation, which is why it is its own aggregate")
        void ticketRoundTrips() {
            Customer customer = persistCustomer();
            Ticket ticket = new Ticket();
            ticket.setCustomer(customer);
            ticket.setCategory("CARD");
            ticket.setTitle("Carte refusee a l etranger");
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setSeverity((short) 2);
            em.persist(ticket);

            em.flush();
            em.clear();

            Ticket loaded = em.find(Ticket.class, ticket.getId());
            assertThat(loaded.getConversation()).isNull();
            assertThat(loaded.getSeverity()).isEqualTo((short) 2);
            assertThat(loaded.getStatus()).isEqualTo(TicketStatus.OPEN);
        }
    }

    @Nested
    @DisplayName("Block 5-8 — knowledge base, control, experimentation and quality")
    class KnowledgeControlAndExperiments {

        private SimulationRun persistRun(long seed) throws Exception {
            Scenario scenario = new Scenario();
            scenario.setName("Scenario " + unique("s"));
            scenario.setLoadProfile(LoadProfile.SATURATED);
            scenario.setDurationMinutes(60);
            scenario.setAdvisorCount(12);
            scenario.setSkillDistribution(
                    objectMapper.readTree("{\"CARDS\":0.4,\"ACCOUNTS\":0.3,\"CREDIT\":0.2,\"FRAUD\":0.1}"));
            scenario.setCustomerProfileMix(
                    objectMapper.readTree("{\"LOW\":0.7,\"MEDIUM\":0.2,\"HIGH\":0.1}"));
            scenario.setInjectedEvents(
                    objectMapper.readTree("[{\"at\":900,\"type\":\"OUTAGE\",\"service\":\"CARD_PAYMENTS\"}]"));
            em.persist(scenario);

            ControlStrategy strategy = new ControlStrategy();
            strategy.setCode(unique("STRAT"));
            strategy.setName("Probe strategy");
            strategy.setKind(StrategyKind.RL);
            strategy.setParams(objectMapper.readTree("{\"algorithm\":\"PPO\"}"));
            em.persist(strategy);

            SimulationRun run = new SimulationRun();
            run.setScenario(scenario);
            run.setStrategy(strategy);
            run.setSeed(seed);
            run.setStatus(RunStatus.COMPLETED);
            em.persist(run);
            return run;
        }

        @Test
        @DisplayName("a KB article with TEXT[] tags and its chunks round-trip")
        void knowledgeBaseRoundTrips() {
            KbArticle article = new KbArticle();
            article.setCategory("CARDS");
            article.setTitle("Faire opposition a votre carte");
            article.setContent("En cas de perte ou de vol de votre carte...");
            article.setTags(new String[] {"carte", "opposition", "fraude"});
            article.setPublished(true);
            em.persist(article);

            KbChunk chunk = new KbChunk();
            chunk.setArticle(article);
            chunk.setChunkIndex(0);
            chunk.setContent("En cas de perte ou de vol de votre carte...");
            em.persist(chunk);

            em.flush();
            em.clear();

            KbArticle loaded = em.find(KbArticle.class, article.getId());
            assertThat(loaded.getTags()).containsExactly("carte", "opposition", "fraude");
            assertThat(loaded.getUpdatedAt()).as("@UpdateTimestamp populated").isNotNull();

            List<KbChunk> chunks = em.createQuery(
                            "select c from KbChunk c where c.article.id = :id", KbChunk.class)
                    .setParameter("id", article.getId())
                    .getResultList();
            // embedding is unmapped by design; the column exists and validate does not care.
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getArticle().getTitle()).isEqualTo("Faire opposition a votre carte");
        }

        @Test
        @DisplayName("operational control entities round-trip, including a JSONB rule condition")
        void operationalControlRoundTrips() throws Exception {
            Skill fraud = em.createQuery("select s from Skill s where s.code = 'FRAUD'", Skill.class)
                    .getSingleResult();

            RoutingRule rule = new RoutingRule();
            rule.setName("Disputed card payments to FRAUD");
            rule.setIntent(Intent.FRAUD);
            rule.setSkill(fraud);
            rule.setPriority(10);
            rule.setConditions(objectMapper.readTree("{\"transactionStatus\":\"DISPUTED\"}"));
            em.persist(rule);

            ServiceIncident incident = new ServiceIncident();
            incident.setService(BankingService.CARD_PAYMENTS);
            incident.setRegion("north");
            incident.setSeverity((short) 1);
            incident.setDescription("Card authorisations failing");
            incident.setStartedAt(Instant.now());
            incident.setAffectedCount(4200);
            em.persist(incident);

            em.flush();
            em.clear();

            RoutingRule loadedRule = em.find(RoutingRule.class, rule.getId());
            assertThat(loadedRule.getConditions().get("transactionStatus").asText()).isEqualTo("DISPUTED");
            assertThat(loadedRule.getSkill().getCode()).isEqualTo("FRAUD");

            // Served by idx_service_incident_active (region, partial on resolved_at IS NULL).
            List<ServiceIncident> active = em.createQuery(
                            "select i from ServiceIncident i where i.region = :r and i.resolvedAt is null",
                            ServiceIncident.class)
                    .setParameter("r", "north")
                    .getResultList();
            assertThat(active).extracting(ServiceIncident::getService).contains(BankingService.CARD_PAYMENTS);
        }

        @Test
        @DisplayName("a run and its KPI share one primary key through @MapsId")
        void runKpiSharesPrimaryKey() throws Exception {
            SimulationRun run = persistRun(1001L);

            RunKpi kpi = new RunKpi();
            kpi.setRun(run); // @MapsId takes the id from the run; runId is never set directly
            kpi.setTotalConversations(1840);
            kpi.setSlaRatio(new BigDecimal("0.8241"));
            kpi.setAbandonRatio(new BigDecimal("0.0612"));
            kpi.setP95WaitSeconds(new BigDecimal("143.50"));
            kpi.setFairnessRatio(new BigDecimal("0.912"));
            em.persist(kpi);

            em.flush();
            em.clear();

            RunKpi loaded = em.find(RunKpi.class, run.getId());
            // The shared key is the assertion: the KPI's id IS the run's id, not a separate value.
            assertThat(loaded.getRunId()).isEqualTo(run.getId());
            assertThat(loaded.getRun().getSeed()).isEqualTo(1001L);
            assertThat(loaded.getSlaRatio()).isEqualByComparingTo("0.8241");

            SimulationRun reloaded = em.find(SimulationRun.class, run.getId());
            assertThat(reloaded.getKpi().getTotalConversations()).isEqualTo(1840);
        }

        @Test
        @DisplayName("metric samples round-trip through their composite key")
        void metricSampleRoundTrips() throws Exception {
            SimulationRun run = persistRun(1002L);
            Skill cards = em.createQuery(
                            "select s from Skill s where s.code = 'CARDS'", Skill.class)
                    .getSingleResult();

            // Written natively, mirroring the batch JDBC path the entity is deliberately unable to
            // take: MetricSample is @Immutable and its repository has no save method.
            em.createNativeQuery(
                            """
                            insert into metric_sample (run_id, sim_time, skill_id, queue_length, avg_wait, available_count, busy_count)
                            values (:rid, 10, :sid, 14, 62.50, 3, 9)
                            """)
                    .setParameter("rid", run.getId())
                    .setParameter("sid", cards.getId())
                    .executeUpdate();
            em.flush();
            em.clear();

            MetricSample sample = em.find(
                    MetricSample.class, new MetricSampleId(run.getId(), 10, cards.getId()));
            assertThat(sample.getQueueLength()).isEqualTo(14);
            assertThat(sample.getAvgWait()).isEqualByComparingTo("62.50");
            assertThat(sample.getSkill().getCode()).isEqualTo("CARDS");
        }

        @Test
        @DisplayName("an agent decision stores the observation, the action and the approval")
        void agentDecisionRoundTrips() throws Exception {
            SimulationRun run = persistRun(1003L);

            AgentDecision decision = new AgentDecision();
            decision.setRun(run);
            decision.setAgentType(AgentType.WORKFORCE_MANAGER);
            decision.setSimTime(120);
            decision.setObservation(objectMapper.readTree(
                    "{\"queue\":{\"FRAUD\":14,\"ACCOUNTS\":3},\"available\":2}"));
            decision.setAction(objectMapper.readTree(
                    "{\"type\":\"REASSIGN\",\"from\":\"ACCOUNTS\",\"to\":\"FRAUD\",\"count\":1}"));
            decision.setReason("Fraud queue above threshold while accounts is idle");
            em.persist(decision);

            em.flush();
            em.clear();

            AgentDecision loaded = em.find(AgentDecision.class, decision.getId());
            // The XAI contract: what it saw and what it did are both replayable.
            assertThat(loaded.getObservation().get("queue").get("FRAUD").asInt()).isEqualTo(14);
            assertThat(loaded.getAction().get("type").asText()).isEqualTo("REASSIGN");
            assertThat(loaded.getAgentType()).isEqualTo(AgentType.WORKFORCE_MANAGER);
            assertThat(loaded.getApprovedBy()).as("acted unsupervised").isNull();
        }

        @Test
        @DisplayName("AI and human evaluations of one conversation coexist, which is what kappa needs")
        void qualityEvaluationsCoexist() throws Exception {
            Customer customer = new Customer();
            customer.setExternalRef(unique("QC"));
            customer.setFirstName("Quality");
            customer.setLastName("Subject");
            customer.setRegion("north");
            em.persist(customer);

            Conversation conversation = new Conversation();
            conversation.setCustomer(customer);
            conversation.setStatus(ConversationStatus.RESOLVED);
            em.persist(conversation);

            QualityEvaluation byAi = new QualityEvaluation();
            byAi.setConversation(conversation);
            byAi.setGlobalScore(new BigDecimal("8.40"));
            byAi.setScores(objectMapper.readTree("{\"RELEVANCE\":9,\"ACCURACY\":8,\"EMPATHY\":8}"));
            byAi.setFlags(objectMapper.readTree("{\"unsourced_claims\":2}"));
            byAi.setEvaluator(EvaluatorType.AI);
            em.persist(byAi);

            QualityEvaluation byHuman = new QualityEvaluation();
            byHuman.setConversation(conversation);
            byHuman.setGlobalScore(new BigDecimal("7.90"));
            byHuman.setScores(objectMapper.readTree("{\"RELEVANCE\":8,\"ACCURACY\":8,\"EMPATHY\":7}"));
            byHuman.setEvaluator(EvaluatorType.HUMAN);
            em.persist(byHuman);

            em.flush();
            em.clear();

            List<QualityEvaluation> both = em.createQuery(
                            "select q from QualityEvaluation q where q.conversation.id = :id",
                            QualityEvaluation.class)
                    .setParameter("id", conversation.getId())
                    .getResultList();
            // One table, two evaluators: the agreement study is a self-join, not a reconciliation.
            assertThat(both).hasSize(2);
            assertThat(both).extracting(QualityEvaluation::getEvaluator)
                    .containsExactlyInAnyOrder(EvaluatorType.AI, EvaluatorType.HUMAN);
            assertThat(both.stream().filter(q -> q.getEvaluator() == EvaluatorType.AI).findFirst())
                    .get()
                    .extracting(q -> q.getFlags().get("unsourced_claims").asInt())
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("V2 reference seed, as re-coded by V3")
    class ReferenceSeed {

        private static final String TEST_SKILLS =
                com.callverse.conversation.ConversationFixtures.TEST_SKILL_PREFIX.replace("_", "\\_") + "%";

        private long seededSkills() {
            return ((Number) em.createNativeQuery("select count(*) from skill where code not like :test")
                    .setParameter("test", TEST_SKILLS)
                    .getSingleResult()).longValue();
        }

        private long seededSlaPolicies() {
            return ((Number) em.createNativeQuery(
                            "select count(*) from sla_policy p left join skill s on s.id = p.skill_id"
                                    + " where s.code is null or s.code not like :test")
                    .setParameter("test", TEST_SKILLS)
                    .getSingleResult()).longValue();
        }

        @Test
        @DisplayName("every reference table is seeded with the documented rows")
        void seedCountsAreCorrect() {
            assertThat(count("BankingProduct")).isEqualTo(5);
            // Native counts for tables whose entities arrive in a later block; replaced with JPQL
            // as those entities land.
            // The conversation tests create private skills (and their policies) in the shared
            // container; they all start with TEST_SKILL_PREFIX, which no migration ever seeds.
            assertThat(seededSkills()).isEqualTo(4);
            assertThat(seededSlaPolicies()).isEqualTo(4);
            assertThat(nativeCount("quality_criterion")).isEqualTo(6);
            assertThat(nativeCount("control_strategy")).isEqualTo(3);
            assertThat(nativeCount("app_user")).isGreaterThanOrEqualTo(4);
        }

        private long nativeCount(String table) {
            return ((Number) em.createNativeQuery("select count(*) from " + table)
                    .getSingleResult())
                    .longValue();
        }

        @Test
        @DisplayName("the skills are the banking ones, and FRAUD carries the strictest SLA")
        void skillsAreBanking() {
            List<Object> codes = em.createNativeQuery("select code from skill where code not like :test order by code")
                    .setParameter("test", TEST_SKILLS)
                    .getResultList();
            assertThat(codes).containsExactly("ACCOUNTS", "CARDS", "CREDIT", "FRAUD");

            Object[] fraud = (Object[]) em.createNativeQuery(
                            """
                            select p.target_seconds, p.target_ratio
                              from sla_policy p join skill s on s.id = p.skill_id
                             where s.code = 'FRAUD'
                            """)
                    .getSingleResult();
            assertThat(((Number) fraud[0]).intValue()).isEqualTo(30);
            assertThat((BigDecimal) fraud[1]).isEqualByComparingTo("0.900");
        }

        @Test
        @DisplayName("quality criterion weights sum to exactly 1.000")
        void qualityWeightsSumToOne() {
            BigDecimal sum = (BigDecimal) em.createNativeQuery(
                            "select sum(weight) from quality_criterion")
                    .getSingleResult();
            // Exactly 1.000, not approximately: a grid whose weights do not sum to 1 produces a
            // global score that cannot be compared across conversations.
            assertThat(sum).isEqualByComparingTo("1.000");
        }

        @Test
        @DisplayName("one test account exists per role")
        void oneAccountPerRole() {
            for (UserRole role : UserRole.values()) {
                Long n = em.createQuery(
                                "select count(u) from AppUser u where u.role = :r", Long.class)
                        .setParameter("r", role)
                        .getSingleResult();
                assertThat(n).as("seeded accounts for %s", role).isGreaterThanOrEqualTo(1);
            }
        }

        private long count(String entity) {
            return em.createQuery("select count(e) from " + entity + " e", Long.class)
                    .getSingleResult();
        }
    }
}
