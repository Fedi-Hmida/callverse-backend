# CallVerse database schema reference

Reconstructed on 18 October 2026 from the checked-in forward migrations because the earlier agreed schema document was absent from this workspace. This is a reviewable record of the current implementation; it does not establish new team approval for banking rules.

Flyway owns provisioning. Existing applied migrations must never be edited. Hibernate remains in validate mode. The full schema evolution below is ordered and includes the V7 ticket/credit extension and V8 refresh sessions. V2 and V4 contain reference/demo data only and are omitted from this schema reference.

Live and simulation records remain separated by customer.is_simulated, advisor.is_simulated and conversation.run_id. New customer registration creates an identity/profile only. The credit ledger books real refunds only through the authorized backend operation.

V7 adds ticket_comment and nullable account_id, transaction_id and request_id to commercial_credit. The partial unique request index makes each conversation/request pair idempotent while preserving historical ledger rows.

V8 stores only SHA-256 hashes of opaque refresh tokens. Consumed hashes detect replay. Each family has a fixed expiry and revocation timestamp; rotating a token does not extend the family. Access JWTs carry the family ID and both REST and STOMP validate that family.

## V1__init.sql  ```sql -- =============================================================================
-- CallVerse — initial schema
--
-- Transcribed from docs/CALLVERSE_DB_SCHEMA.md, which is the authoritative
-- source of truth and is shared with whoever provisions the Neon database.
-- Block numbering below matches that document's block numbering so the two can
-- be read side by side. Nothing here deviates from it.
--
-- Lines marked "-- REVIEW:" are observations raised against the schema document
-- for its author to decide on. They are comments only and change no DDL; the
-- schema this file produces is exactly what the document specifies.
--
-- Two universes coexist and must not be conflated:
--   * business universe  — customers, contracts, conversations, tickets.
--                          Moderate volume, JPA repositories are appropriate.
--   * experiment universe — runs, decisions, metrics. Burst writes at volume;
--                          batch JDBC only.
-- The pivot is conversation.run_id: NULL means live, non-null means simulation.
-- =============================================================================

-- Extensions ------------------------------------------------------------------
-- gen_random_uuid() for UUID defaults; vector for the RAG knowledge base.
-- The schema document declares "vector" inline in Block 5; both are hoisted here
-- so that extension creation happens once, before any table needs them.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;


-- =============================================================================
-- Block 1 — Identity and users
-- =============================================================================

CREATE TABLE app_user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(180) NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,
    first_name      VARCHAR(80)  NOT NULL,
    last_name       VARCHAR(80)  NOT NULL,
    role            VARCHAR(20)  NOT NULL
                    CHECK (role IN ('CUSTOMER','ADVISOR','SUPERVISOR','ADMIN')),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Partial index: inactive users are never routed to or authenticated.
CREATE INDEX idx_user_role ON app_user(role) WHERE active;


-- =============================================================================
-- Block 2 — Customer domain
-- =============================================================================

-- user_id is nullable and is_simulated exists because simulated customers have
-- no account. This flag is what lets live and simulation mode share one database
-- without polluting business statistics.
CREATE TABLE customer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES app_user(id) ON DELETE SET NULL,
    external_ref    VARCHAR(40) NOT NULL UNIQUE,
    first_name      VARCHAR(80) NOT NULL,
    last_name       VARCHAR(80) NOT NULL,
    phone           VARCHAR(30),
    zone            VARCHAR(40) NOT NULL,
    tenure_months   INT         NOT NULL DEFAULT 0,
    churn_risk      VARCHAR(10) NOT NULL DEFAULT 'LOW'
                    CHECK (churn_risk IN ('LOW','MEDIUM','HIGH')),
    is_simulated    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customer_zone ON customer(zone);
-- Partial: the overwhelming majority of customers are LOW and are never the
-- subject of a churn query, so they are kept out of the index entirely.
CREATE INDEX idx_customer_churn ON customer(churn_risk) WHERE churn_risk <> 'LOW';

CREATE TABLE plan (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(40)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    category        VARCHAR(20)  NOT NULL
                    CHECK (category IN ('MOBILE','FIBER','ADSL','BUNDLE')),
    monthly_price   NUMERIC(8,2) NOT NULL,
    data_gb         INT,
    speed_mbps      INT,
    active          BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE contract (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    plan_id         UUID NOT NULL REFERENCES plan(id),
    status          VARCHAR(20) NOT NULL
                    CHECK (status IN ('ACTIVE','SUSPENDED','TERMINATED')),
    started_at      DATE NOT NULL,
    ended_at        DATE,
    CONSTRAINT chk_contract_dates CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE INDEX idx_contract_customer ON contract(customer_id);

CREATE TABLE invoice (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id     UUID NOT NULL REFERENCES contract(id) ON DELETE CASCADE,
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,
    amount          NUMERIC(10,2) NOT NULL,
    status          VARCHAR(20) NOT NULL
                    CHECK (status IN ('PENDING','PAID','OVERDUE','DISPUTED')),
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- DESC on period_start: invoice lists are always most-recent-first.
CREATE INDEX idx_invoice_contract_period ON invoice(contract_id, period_start DESC);


-- =============================================================================
-- Block 3 — Center resources
-- =============================================================================

CREATE TABLE skill (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code     VARCHAR(30) NOT NULL UNIQUE,   -- TECHNICAL, BILLING, COMMERCIAL
    label    VARCHAR(80) NOT NULL
);

-- credit_limit is per advisor. This is the ceiling the backend enforces when the
-- AI agent calls apply_credit. The agent is never the authority on this rule.
CREATE TABLE advisor (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES app_user(id) ON DELETE SET NULL,
    display_name    VARCHAR(120) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE'
                    CHECK (status IN ('AVAILABLE','BUSY','BREAK','OFFLINE')),
    max_concurrent  INT          NOT NULL DEFAULT 1,
    credit_limit    NUMERIC(8,2) NOT NULL DEFAULT 15.00,
    is_simulated    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Association with a payload (level), therefore an entity with an @EmbeddedId
-- rather than a plain @ManyToMany.
CREATE TABLE advisor_skill (
    advisor_id   UUID NOT NULL REFERENCES advisor(id) ON DELETE CASCADE,
    skill_id     UUID NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    level        SMALLINT NOT NULL CHECK (level BETWEEN 1 AND 3),
    PRIMARY KEY (advisor_id, skill_id)
);


-- =============================================================================
-- Block 4 — Interaction (the core)
-- =============================================================================

-- Three decisions to understand:
--  1. run_id is nullable and deliberately NOT a foreign key. It links a
--     conversation to a simulation run; NULL means live mode.
--  2. wait_seconds, handle_seconds and sla_met are deliberately denormalized.
--     Recomputing them on every KPI query over tens of thousands of rows would
--     be ruinous; they are computed once, at the state transition.
--  3. idx_conv_status_queue is the most heavily used index in the application:
--     it serves the "next conversation to assign" query.
--
-- REVIEW: channel and intent have enums in the schema document's enum table but
-- no CHECK constraint here, unlike every other status column. Left as specified.
CREATE TABLE conversation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    advisor_id      UUID REFERENCES advisor(id) ON DELETE SET NULL,
    skill_id        UUID REFERENCES skill(id),
    run_id          UUID,                    -- NULL in live mode
    channel         VARCHAR(20) NOT NULL DEFAULT 'CHAT',
    status          VARCHAR(20) NOT NULL
                    CHECK (status IN ('QUEUED','ASSIGNED','ACTIVE',
                                      'ESCALATED','RESOLVED','ABANDONED')),
    intent          VARCHAR(20),
    priority_score  NUMERIC(6,2) NOT NULL DEFAULT 0,
    queued_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assigned_at     TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    wait_seconds    INT,
    handle_seconds  INT,
    sla_met         BOOLEAN
);

-- Column order is load-bearing: equality on status, equality on skill_id, then
-- the priority_score sort. Reordering these columns breaks the queue query.
CREATE INDEX idx_conv_status_queue ON conversation(status, skill_id, priority_score DESC);
CREATE INDEX idx_conv_run ON conversation(run_id) WHERE run_id IS NOT NULL;
CREATE INDEX idx_conv_customer ON conversation(customer_id, queued_at DESC);

-- BIGSERIAL, not UUID: highest-volume table, and a sequential integer is more
-- compact and faster in the index. sources and tool_calls are JSONB because
-- their shape varies and they are never joined on, only read.
CREATE TABLE message (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    sender           VARCHAR(20) NOT NULL
                     CHECK (sender IN ('CUSTOMER','ADVISOR','SYSTEM')),
    content          TEXT NOT NULL,
    ai_generated     BOOLEAN NOT NULL DEFAULT FALSE,
    sources          JSONB,       -- KB articles used
    tool_calls       JSONB,       -- tools invoked
    sent_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_message_conv ON message(conversation_id, sent_at);

CREATE TABLE ticket (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversation(id) ON DELETE SET NULL,
    category        VARCHAR(30) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    status          VARCHAR(20) NOT NULL
                    CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
    severity        SMALLINT NOT NULL DEFAULT 3 CHECK (severity BETWEEN 1 AND 5),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX idx_ticket_customer ON ticket(customer_id, created_at DESC);

CREATE TABLE commercial_credit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversation(id) ON DELETE SET NULL,
    amount          NUMERIC(8,2) NOT NULL CHECK (amount > 0),
    reason          VARCHAR(255) NOT NULL,
    granted_by      UUID REFERENCES advisor(id),
    approved_by     UUID REFERENCES app_user(id),   -- set when over the ceiling
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- REVIEW: status has an EscalationStatus enum (PENDING, RESOLVED) in the schema
-- document but no CHECK constraint here. Left as specified.
CREATE TABLE escalation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    reason          VARCHAR(255) NOT NULL,
    raised_by       VARCHAR(20) NOT NULL CHECK (raised_by IN ('ADVISOR','AI','RULE')),
    resolved_by     UUID REFERENCES app_user(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ
);


-- =============================================================================
-- Block 5 — Knowledge base (RAG)
--
-- The "vector" extension the schema document declares here is created at the top
-- of this file, before any table needs it.
--
-- pgvector rather than a separate vector service: PostgreSQL is already there
-- and the extension is available on Neon. The Angular back-office writes
-- kb_article, a job recomputes chunks and embeddings, the RAG reads kb_chunk.
-- =============================================================================

CREATE TABLE kb_article (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category    VARCHAR(40)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    content     TEXT         NOT NULL,
    tags        TEXT[],
    version     INT          NOT NULL DEFAULT 1,
    published   BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- REVIEW: no uniqueness on (article_id, chunk_index), so a re-run of the
-- chunking job can silently duplicate chunks. Left as specified.
CREATE TABLE kb_chunk (
    id          BIGSERIAL PRIMARY KEY,
    article_id  UUID NOT NULL REFERENCES kb_article(id) ON DELETE CASCADE,
    chunk_index INT  NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(384)
);

CREATE INDEX idx_kb_chunk_vec ON kb_chunk USING hnsw (embedding vector_cosine_ops);


-- =============================================================================
-- Block 6 — Operational control
-- =============================================================================

-- REVIEW: no uniqueness among active rows per skill_id, so two active policies
-- for one skill are representable with no defined winner. Left as specified.
CREATE TABLE sla_policy (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id        UUID REFERENCES skill(id),
    target_seconds  INT NOT NULL,            -- e.g. 60
    target_ratio    NUMERIC(4,3) NOT NULL,   -- e.g. 0.800
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE routing_rule (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(80) NOT NULL,
    intent      VARCHAR(20),
    skill_id    UUID REFERENCES skill(id),
    priority    INT NOT NULL DEFAULT 100,
    conditions  JSONB,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

-- REVIEW: type has no CHECK constraint and no enum in the document's enum table.
-- Left as specified.
CREATE TABLE network_incident (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    zone            VARCHAR(40) NOT NULL,
    type            VARCHAR(30) NOT NULL,
    severity        SMALLINT NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    estimated_end   TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    run_id          UUID,
    affected_count  INT
);

CREATE INDEX idx_incident_zone_active ON network_incident(zone) WHERE resolved_at IS NULL;


-- =============================================================================
-- Block 7 — Experimentation (high volume)
-- =============================================================================

CREATE TABLE control_strategy (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(40) NOT NULL UNIQUE,  -- STATIC_FIFO, THRESHOLD, RL_PPO_V1
    name        VARCHAR(120) NOT NULL,
    kind        VARCHAR(20) NOT NULL CHECK (kind IN ('BASELINE','HEURISTIC','RL')),
    params      JSONB
);

CREATE TABLE scenario (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(120) NOT NULL,
    load_profile         VARCHAR(20) NOT NULL
                         CHECK (load_profile IN ('LOW','MEDIUM','SATURATED')),
    duration_minutes     INT NOT NULL,
    advisor_count        INT NOT NULL,
    skill_distribution   JSONB NOT NULL,
    customer_profile_mix JSONB NOT NULL,
    injected_events      JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- uq_run guarantees reproducibility: one (scenario, strategy, seed) triple can
-- exist only once. This is what makes the experimental comparison defensible.
CREATE TABLE simulation_run (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id     UUID NOT NULL REFERENCES scenario(id),
    strategy_id     UUID NOT NULL REFERENCES control_strategy(id),
    seed            BIGINT NOT NULL,
    status          VARCHAR(20) NOT NULL
                    CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    error_message   TEXT,
    CONSTRAINT uq_run UNIQUE (scenario_id, strategy_id, seed)
);

-- One row per run; run_id is both PK and FK (shared primary key, @MapsId).
-- This table feeds every chart in the report: raw data is never queried for a
-- comparison table.
CREATE TABLE run_kpi (
    run_id              UUID PRIMARY KEY REFERENCES simulation_run(id) ON DELETE CASCADE,
    total_conversations INT,
    avg_wait_seconds    NUMERIC(8,2),
    p95_wait_seconds    NUMERIC(8,2),
    sla_ratio           NUMERIC(5,4),
    abandon_ratio       NUMERIC(5,4),
    avg_handle_seconds  NUMERIC(8,2),
    occupancy_ratio     NUMERIC(5,4),
    estimated_cost      NUMERIC(10,2),
    avg_quality_score   NUMERIC(4,2),
    fairness_ratio      NUMERIC(6,3),
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Volumetry: a 60-minute run sampled every 10s across 3 skills, over the full
-- matrix (3 loads x 4 strategies x 5 seeds = 60 runs), is on the order of
-- 650k rows at 1s sampling. Therefore: sample every 10 seconds, write in
-- batches of 500 via JdbcTemplate.batchUpdate, and persist nothing for RL
-- training runs — only evaluation runs reach this table.
--
-- REVIEW: skill_id is written as a nullable FK but is part of the primary key,
-- and PostgreSQL forces PK columns NOT NULL. A cross-skill aggregate sample
-- (skill_id IS NULL) is therefore not representable. Left as specified; the
-- resulting schema is identical to the document's.
CREATE TABLE metric_sample (
    run_id          UUID NOT NULL REFERENCES simulation_run(id) ON DELETE CASCADE,
    sim_time        INT  NOT NULL,            -- seconds since run start
    skill_id        UUID REFERENCES skill(id),
    queue_length    INT,
    avg_wait        NUMERIC(8,2),
    available_count INT,
    busy_count      INT,
    PRIMARY KEY (run_id, sim_time, skill_id)
);

-- The XAI table. Every Workforce Manager decision is traced with what it
-- observed, what it did, and why.
--
-- REVIEW: agent_type has an AgentType enum in the document but no CHECK
-- constraint here. Left as specified.
CREATE TABLE agent_decision (
    id           BIGSERIAL PRIMARY KEY,
    run_id       UUID REFERENCES simulation_run(id) ON DELETE CASCADE,
    agent_type   VARCHAR(30) NOT NULL,
    sim_time     INT,
    observation  JSONB NOT NULL,
    action       JSONB NOT NULL,
    reason       TEXT,
    approved_by  UUID REFERENCES app_user(id),   -- human-in-the-loop
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_decision_run ON agent_decision(run_id, sim_time);


-- =============================================================================
-- Block 8 — Quality
-- =============================================================================

CREATE TABLE quality_criterion (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(40) NOT NULL UNIQUE,
    label       VARCHAR(120) NOT NULL,
    weight      NUMERIC(4,3) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

-- evaluator is what allows AI evaluations and the 30-50 human annotations to
-- live in one table, so Cohen's kappa is computed with a simple join. Scores are
-- JSONB because the grid is configurable: there is deliberately no column per
-- criterion.
--
-- REVIEW: no uniqueness on (conversation_id, evaluator), so two AI evaluations
-- of one conversation are representable and would fan out the kappa join.
-- Left as specified.
CREATE TABLE quality_evaluation (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    global_score     NUMERIC(4,2) NOT NULL,
    scores           JSONB NOT NULL,    -- {criterion_code: score}
    explanation      TEXT,
    flags            JSONB,             -- e.g. {"unsourced_claims": 2}
    evaluator        VARCHAR(20) NOT NULL CHECK (evaluator IN ('AI','HUMAN')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_quality_conv ON quality_evaluation(conversation_id);
 ```
## V3__banking_domain.sql  ```sql -- =============================================================================
-- V3 - the platform becomes a retail bank
-- =============================================================================
-- Decision of 2026-09-30: CallVerse is dedicated to retail banking and no longer
-- models a telecom operator. The customer-relation core (conversations, advisors,
-- skills, tickets, escalations, knowledge base, SLA, experiments, quality) is
-- domain-neutral and stays. What only made sense for a telecom operator leaves:
-- tariff plans, subscriptions, monthly invoices and network incidents.
--
-- Forward-only, on purpose. V1 and V2 are already applied on Neon; editing them
-- changes their checksum and Flyway then refuses to start.
--
-- DATA LOSS. Dropping plan, contract, invoice and network_incident destroys every
-- row in them. Only test data is known to exist; the schema owner confirms that
-- before applying this migration to Neon.
--
-- Safe to run against a hand-provisioned database: IF EXISTS / IF NOT EXISTS where
-- PostgreSQL allows it, drops ordered by foreign key with no CASCADE (CASCADE can
-- silently drop constraints this file does not name), idempotent seeds.
-- =============================================================================


-- --- 1. Telecom-only tables ---------------------------------------------------
-- Order follows the foreign keys: invoice -> contract -> plan.

DROP TABLE IF EXISTS invoice;
DROP TABLE IF EXISTS contract;
DROP TABLE IF EXISTS plan;
DROP TABLE IF EXISTS network_incident;


-- --- 2. Customer ---------------------------------------------------------------
-- The network zone becomes the customer's region: banking outages and branch
-- networks are regional too, and the column keeps its NOT NULL and its index.
-- segment is the "client value" factor of the queue priority score.

-- Guarded, because PostgreSQL has no RENAME COLUMN IF EXISTS: a schema owner who
-- applied this by hand before Flyway runs it must not see the second run fail.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'customer' AND column_name = 'zone') THEN
        ALTER TABLE customer RENAME COLUMN zone TO region;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_customer_zone' AND relkind = 'i')
       AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'idx_customer_region') THEN
        ALTER INDEX idx_customer_zone RENAME TO idx_customer_region;
    END IF;
END $$;

ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS segment VARCHAR(20) NOT NULL DEFAULT 'MASS'
        CHECK (segment IN ('MASS','AFFLUENT','PRIVATE','PROFESSIONAL'));


-- --- 3. Product catalogue --------------------------------------------------------
-- Reference data. Withdrawn products stay: open accounts still reference them.

CREATE TABLE IF NOT EXISTS banking_product (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code           VARCHAR(40)  NOT NULL UNIQUE,
    name           VARCHAR(120) NOT NULL,
    category       VARCHAR(20)  NOT NULL
                   CHECK (category IN ('CURRENT_ACCOUNT','SAVINGS','CONSUMER_LOAN','MORTGAGE')),
    monthly_fee    NUMERIC(8,2) NOT NULL DEFAULT 0 CHECK (monthly_fee >= 0),
    interest_rate  NUMERIC(6,4) CHECK (interest_rate >= 0),   -- annual, as a fraction; NULL = none
    active         BOOLEAN      NOT NULL DEFAULT TRUE
);


-- --- 4. Accounts ---------------------------------------------------------------
-- A loan is an account too, on a loan product, carrying its outstanding balance.

CREATE TABLE IF NOT EXISTS account (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id      UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    product_id       UUID NOT NULL REFERENCES banking_product(id),
    iban             VARCHAR(34)   NOT NULL UNIQUE,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
    balance          NUMERIC(14,2) NOT NULL DEFAULT 0,
    overdraft_limit  NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (overdraft_limit >= 0),
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    opened_at        DATE NOT NULL,
    closed_at        DATE,
    CONSTRAINT chk_account_dates CHECK (closed_at IS NULL OR closed_at >= opened_at)
);
CREATE INDEX IF NOT EXISTS idx_account_customer ON account(customer_id);


-- --- 5. Cards ------------------------------------------------------------------
-- PCI-DSS: the full card number (PAN), the CVV and the PIN are never stored.
-- pan_last4 is all a customer or an advisor needs to identify a card.

CREATE TABLE IF NOT EXISTS card (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    pan_last4     VARCHAR(4)    NOT NULL CHECK (pan_last4 ~ '^[0-9]{4}$'),
    network       VARCHAR(20)   NOT NULL CHECK (network IN ('VISA','MASTERCARD')),
    type          VARCHAR(10)   NOT NULL CHECK (type IN ('DEBIT','CREDIT')),
    status        VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE','BLOCKED','EXPIRED','CANCELLED')),
    expires_on    DATE          NOT NULL,
    daily_limit   NUMERIC(10,2) NOT NULL CHECK (daily_limit > 0),
    blocked_at    TIMESTAMPTZ,
    block_reason  VARCHAR(30)
                  CHECK (block_reason IN ('LOST','STOLEN','FRAUD_SUSPECTED','CUSTOMER_REQUEST')),
    -- A blocked card always says when and why: that is what a fraud or loss call is about.
    CONSTRAINT chk_card_blocked
        CHECK (status <> 'BLOCKED' OR (blocked_at IS NOT NULL AND block_reason IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_card_account ON card(account_id);


-- --- 6. Transactions -----------------------------------------------------------
-- amount is signed: a negative value debits the account.

CREATE TABLE IF NOT EXISTS bank_transaction (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    card_id       UUID REFERENCES card(id) ON DELETE SET NULL,
    type          VARCHAR(20)   NOT NULL
                  CHECK (type IN ('CARD_PAYMENT','ATM_WITHDRAWAL','TRANSFER_IN','TRANSFER_OUT',
                                  'DIRECT_DEBIT','FEE','INTEREST','REFUND')),
    amount        NUMERIC(14,2) NOT NULL CHECK (amount <> 0),
    currency      VARCHAR(3)    NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
    label         VARCHAR(140)  NOT NULL,
    counterparty  VARCHAR(140),
    status        VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING','BOOKED','REJECTED','DISPUTED')),
    booked_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_txn_account_booked ON bank_transaction(account_id, booked_at DESC);


-- --- 7. Service incidents --------------------------------------------------------
-- Replaces network_incident: an outage of a banking service, optionally regional.
-- run_id mirrors conversation.run_id: NULL in live mode, no foreign key.

CREATE TABLE IF NOT EXISTS service_incident (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service         VARCHAR(30) NOT NULL
                    CHECK (service IN ('CARD_PAYMENTS','ONLINE_BANKING','MOBILE_APP',
                                       'ATM_NETWORK','TRANSFERS')),
    region          VARCHAR(40),                   -- NULL: affects every region
    severity        SMALLINT NOT NULL CHECK (severity BETWEEN 1 AND 5),
    description     VARCHAR(255),
    started_at      TIMESTAMPTZ NOT NULL,
    estimated_end   TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    run_id          UUID,
    affected_count  INT CHECK (affected_count >= 0),
    CONSTRAINT chk_incident_dates CHECK (resolved_at IS NULL OR resolved_at >= started_at)
);
-- Every lookup filters unresolved incidents by region (or asks for all of them);
-- none filters by service, so the partial index leads with region.
CREATE INDEX IF NOT EXISTS idx_service_incident_active
    ON service_incident(region) WHERE resolved_at IS NULL;


-- --- 8. Reference data -----------------------------------------------------------
-- Skills are re-coded in place rather than deleted, so every foreign key that
-- already points at them (advisor_skill, sla_policy, routing_rule, conversation,
-- metric_sample) keeps pointing at the right row.

-- Each re-code is skipped if its target code already exists, so a database where
-- someone already added the banking skill does not hit the UNIQUE constraint.
UPDATE skill SET code = 'ACCOUNTS', label = 'Comptes et paiements'
 WHERE code = 'BILLING'    AND NOT EXISTS (SELECT 1 FROM skill WHERE code = 'ACCOUNTS');
UPDATE skill SET code = 'CARDS',    label = 'Cartes'
 WHERE code = 'TECHNICAL'  AND NOT EXISTS (SELECT 1 FROM skill WHERE code = 'CARDS');
UPDATE skill SET code = 'CREDIT',   label = 'Credit'
 WHERE code = 'COMMERCIAL' AND NOT EXISTS (SELECT 1 FROM skill WHERE code = 'CREDIT');

INSERT INTO skill (code, label) VALUES ('FRAUD', 'Fraude et securite')
ON CONFLICT (code) DO NOTHING;

-- Fraud is time-critical: a compromised card keeps being used while the customer
-- waits. Its target is stricter than the 60 s / 80 % the other skills carry.
INSERT INTO sla_policy (skill_id, target_seconds, target_ratio, active)
SELECT s.id, 30, 0.900, TRUE
FROM skill s
WHERE s.code = 'FRAUD'
  AND NOT EXISTS (SELECT 1 FROM sla_policy p WHERE p.skill_id = s.id);

-- Intents carry no CHECK constraint, so old telecom values would survive and then
-- fail to load into the banking Intent enum. They become OTHER.
UPDATE conversation SET intent = 'OTHER'
 WHERE intent IN ('BILLING','TECHNICAL','COMMERCIAL','CHURN');
UPDATE routing_rule SET intent = 'OTHER'
 WHERE intent IN ('BILLING','TECHNICAL','COMMERCIAL','CHURN');

INSERT INTO banking_product (code, name, category, monthly_fee, interest_rate, active) VALUES
    ('CUR_ESSENTIAL', 'Compte courant Essentiel', 'CURRENT_ACCOUNT', 2.00, NULL,   TRUE),
    ('CUR_PREMIUM',   'Compte courant Premium',   'CURRENT_ACCOUNT', 9.90, NULL,   TRUE),
    ('SAV_LIVRET',    'Livret epargne',           'SAVINGS',         0.00, 0.0300, TRUE),
    ('LOAN_PERSONAL', 'Pret personnel',           'CONSUMER_LOAN',   0.00, 0.0590, TRUE),
    ('MORTGAGE_20Y',  'Pret immobilier 20 ans',   'MORTGAGE',        0.00, 0.0350, TRUE)
ON CONFLICT (code) DO NOTHING;
 ```
## V7__support_followup_and_credit_ledger.sql  ```sql -- Forward-only extension: existing ticket and credit rows are preserved.
CREATE TABLE ticket_comment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    author_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    author_role VARCHAR(20) NOT NULL CHECK (author_role IN ('CUSTOMER','ADVISOR','SUPERVISOR','ADMIN')),
    content VARCHAR(4000) NOT NULL CHECK (length(trim(content)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ticket_comment_ticket ON ticket_comment(ticket_id, created_at, id);

ALTER TABLE commercial_credit ADD COLUMN account_id UUID REFERENCES account(id);
ALTER TABLE commercial_credit ADD COLUMN transaction_id UUID REFERENCES bank_transaction(id);
ALTER TABLE commercial_credit ADD COLUMN request_id UUID;
CREATE UNIQUE INDEX uq_commercial_credit_request ON commercial_credit(conversation_id, request_id)
    WHERE request_id IS NOT NULL;
 ```
## V8__refresh_sessions.sql  ```sql -- Opaque refresh tokens rotate on use. Retain consumed hashes to detect replay.
-- The fixed family expiry bounds token history and does not extend on rotation.
CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    issued_role VARCHAR(20) NOT NULL CHECK (issued_role IN ('CUSTOMER','ADVISOR','SUPERVISOR','ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_auth_session_user ON auth_session(user_id);
CREATE TABLE auth_refresh_token (
    token_hash CHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_session(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_auth_refresh_session ON auth_refresh_token(session_id);
 ```