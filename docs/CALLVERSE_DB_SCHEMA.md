# CallVerse — Database Schema Reference

**Status:** authoritative. This document is the single source of truth for the CallVerse
database schema. The Flyway migration `V1__init.sql` and every JPA entity must match it
exactly. If you believe something here is wrong, say so — do not silently change it.

**Database:** PostgreSQL 16 on Neon (serverless).
**Extensions required:** `pgcrypto` (for `gen_random_uuid()`), `vector` (pgvector, for the
RAG knowledge base).

---

## Design principle — two universes in one database

Two families of tables coexist and must never be conflated:

- **Business universe** — customers, contracts, conversations, tickets. Moderate volume,
  normal write patterns, JPA repositories are appropriate.
- **Experiment universe** — runs, decisions, metrics. Potentially enormous volume, burst
  writes. Batch JDBC writes only; JPA row-by-row writes here are prohibitively slow on Neon.

The pivot between the two is `conversation.run_id`: `NULL` means live mode, non-null means
the conversation belongs to a simulation run.

---

## Block 1 — Identity and users

```sql
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
CREATE INDEX idx_user_role ON app_user(role) WHERE active;
```

**Note:** one role per user. A join table would add complexity for no benefit at this scale.

---

## Block 2 — Customer domain

```sql
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
CREATE INDEX idx_customer_churn ON customer(churn_risk) WHERE churn_risk <> 'LOW';
```

**Note:** `user_id` is nullable and `is_simulated` exists because **simulated customers have
no account**. This flag is what lets live mode and simulation mode share one database without
polluting business statistics.

```sql
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
CREATE INDEX idx_invoice_contract_period ON invoice(contract_id, period_start DESC);
```

---

## Block 3 — Center resources

```sql
CREATE TABLE skill (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code     VARCHAR(30) NOT NULL UNIQUE,   -- TECHNICAL, BILLING, COMMERCIAL
    label    VARCHAR(80) NOT NULL
);

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

CREATE TABLE advisor_skill (
    advisor_id   UUID NOT NULL REFERENCES advisor(id) ON DELETE CASCADE,
    skill_id     UUID NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    level        SMALLINT NOT NULL CHECK (level BETWEEN 1 AND 3),
    PRIMARY KEY (advisor_id, skill_id)
);
```

**Note:** `credit_limit` is per advisor. This is the ceiling the backend enforces when the
AI agent calls `apply_credit`. The agent is never the authority on this rule.

**Note:** `advisor_skill` is an association table with a payload (`level`). It must be
modelled as an entity with an `@EmbeddedId`, not as a plain `@ManyToMany`.

---

## Block 4 — Interaction (the core)

```sql
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
CREATE INDEX idx_conv_status_queue ON conversation(status, skill_id, priority_score DESC);
CREATE INDEX idx_conv_run ON conversation(run_id) WHERE run_id IS NOT NULL;
CREATE INDEX idx_conv_customer ON conversation(customer_id, queued_at DESC);
```

**Three decisions to understand:**

1. `run_id` is nullable and **deliberately not a foreign key** at this stage — it links a
   conversation to a simulation run, `NULL` meaning live mode. Adding the FK constraint is
   acceptable; carrying it as a plain UUID is also acceptable. Decide, state your choice.
2. `wait_seconds`, `handle_seconds` and `sla_met` are **deliberately denormalized**.
   Recomputing them on every KPI query over tens of thousands of conversations would be
   ruinous. They are computed once, at the state transition.
3. `idx_conv_status_queue` is the most heavily used index in the whole application: it
   serves the "next conversation to assign" query.

**State machine (enforced in the domain, not only by the CHECK constraint):**

```
QUEUED ──> ASSIGNED ──> ACTIVE ──> RESOLVED
   │           │           │
   │           │           └──> ESCALATED ──> RESOLVED
   └───────────┴──────────────> ABANDONED
```

Any other transition must raise `INVALID_STATE_TRANSITION`.

```sql
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
```

**Note:** `BIGSERIAL`, not UUID — this is the highest-volume table and a sequential integer
is more compact and faster in the index. `sources` and `tool_calls` are JSONB because their
shape varies and they are never joined on, only read.

```sql
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
```

---

## Block 5 — Knowledge base (RAG)

```sql
CREATE EXTENSION IF NOT EXISTS vector;

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

CREATE TABLE kb_chunk (
    id          BIGSERIAL PRIMARY KEY,
    article_id  UUID NOT NULL REFERENCES kb_article(id) ON DELETE CASCADE,
    chunk_index INT  NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(384)
);
CREATE INDEX idx_kb_chunk_vec ON kb_chunk USING hnsw (embedding vector_cosine_ops);
```

**Note:** `pgvector` rather than a separate vector service — PostgreSQL is already there and
the extension is available on Neon. The Angular back-office writes `kb_article`, a job
recomputes chunks and embeddings, the RAG reads `kb_chunk`. One source of truth.

**Note on JPA:** `tags TEXT[]` and `embedding vector(384)` have no native Hibernate mapping.
`TEXT[]` needs a custom type or `columnDefinition`. `vector(384)` should **not** be mapped in
JPA at all in this phase — mark it `insertable=false, updatable=false` or omit the field and
handle embeddings through native queries later. State what you chose.

---

## Block 6 — Operational control

```sql
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
```

---

## Block 7 — Experimentation (high volume)

```sql
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
```

**Note:** `uq_run` guarantees reproducibility — one (scenario, strategy, seed) triple can
exist only once. This is what makes the experimental comparison defensible.

```sql
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
```

**Note:** one row per run, `run_id` is both PK and FK (shared primary key, `@MapsId`). This
table feeds every chart in the report — raw data is never queried for a comparison table.

```sql
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
```

**Volumetry warning — this drives a design decision.** A 60-minute run sampled every second
across 3 skills produces 10,800 rows. The full matrix (3 loads x 4 strategies x 5 seeds =
60 runs) is roughly **650,000 rows**. On Neon, unit inserts are slow and costly. Therefore:

1. Sample every **10 seconds**, not every second.
2. Write in **batch** via `JdbcTemplate.batchUpdate`, lots of 500 — **never row-by-row
   through JPA**.
3. RL **training** runs (thousands of episodes) persist **nothing**. Only evaluation runs
   reach the database.

Consequence for the entity layer: `metric_sample` gets a read-only JPA entity (composite
`@EmbeddedId`) for queries, and a separate JDBC-based batch writer. Do not use
`JpaRepository.saveAll` on it.

```sql
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
```

**Note:** this is the XAI table. Every Workforce Manager decision is traced with what it
observed, what it did, and why.

---

## Block 8 — Quality

```sql
CREATE TABLE quality_criterion (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(40) NOT NULL UNIQUE,
    label       VARCHAR(120) NOT NULL,
    weight      NUMERIC(4,3) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

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

**Note:** `evaluator` is what allows AI evaluations and the 30-50 human annotations to live
in one table, so Cohen's kappa is computed with a simple join. Scores are JSONB because the
grid is configurable — do not create one column per criterion.

---

## Enumerations to create in `core.domain.enums`

| Enum | Values |
|---|---|
| `UserRole` | CUSTOMER, ADVISOR, SUPERVISOR, ADMIN |
| `ChurnRisk` | LOW, MEDIUM, HIGH |
| `PlanCategory` | MOBILE, FIBER, ADSL, BUNDLE |
| `ContractStatus` | ACTIVE, SUSPENDED, TERMINATED |
| `InvoiceStatus` | PENDING, PAID, OVERDUE, DISPUTED |
| `AdvisorStatus` | AVAILABLE, BUSY, BREAK, OFFLINE |
| `ConversationStatus` | QUEUED, ASSIGNED, ACTIVE, ESCALATED, RESOLVED, ABANDONED |
| `Intent` | BILLING, TECHNICAL, COMMERCIAL, CHURN, OTHER |
| `Channel` | CHAT (extensible) |
| `MessageSender` | CUSTOMER, ADVISOR, SYSTEM |
| `TicketStatus` | OPEN, IN_PROGRESS, RESOLVED, CLOSED |
| `EscalationRaisedBy` | ADVISOR, AI, RULE |
| `EscalationStatus` | PENDING, RESOLVED |
| `LoadProfile` | LOW, MEDIUM, SATURATED |
| `StrategyKind` | BASELINE, HEURISTIC, RL |
| `RunStatus` | PENDING, RUNNING, COMPLETED, FAILED |
| `AgentType` | CLIENT_SIMULATOR, CUSTOMER_ADVISOR, WORKFORCE_MANAGER, QUALITY_ANALYST |
| `EvaluatorType` | AI, HUMAN |
| `SkillCode` | TECHNICAL, BILLING, COMMERCIAL (reference data, may stay a table only) |

All enums are persisted as `@Enumerated(EnumType.STRING)` — never `ORDINAL`. The database
`CHECK` constraints must match the enum values exactly.

---

## Reference seed data (`V2__seed_reference.sql`)

- **skill** — TECHNICAL / Technique, BILLING / Facturation, COMMERCIAL / Commercial
- **plan** — at least 4 plans across the categories
- **sla_policy** — one per skill, `target_seconds = 60`, `target_ratio = 0.800`
- **quality_criterion** — relevance, accuracy, compliance, communication, empathy,
  resolution; weights summing to 1.000
- **control_strategy** — `STATIC_FIFO` (BASELINE), `THRESHOLD` (HEURISTIC), `RL_PPO_V1` (RL)
- **app_user** — one test account per role, password hash for a documented dev password

---

## Neon-specific constraints

| Aspect | Consequence |
|---|---|
| Serverless, cold start after inactivity | Never start a demo cold; ping first |
| Low connection ceiling | HikariCP `maximum-pool-size: 8`, not the default |
| Pooled vs direct endpoint | App uses pooled; Flyway uses direct |
| Storage separated from compute | Higher write latency; batch writes are mandatory |
| Extensions | `pgcrypto` and `vector` both available |
| Branches | Use a branch per developer, plus a frozen `demo` branch |
