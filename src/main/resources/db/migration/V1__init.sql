-- =============================================================================
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
