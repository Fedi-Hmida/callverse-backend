# CallVerse backend — project state

**Read this first after any pause.** Everything else in `docs/` is older than this file.

| | |
|---|---|
| **Document date** | 2026-09-24 |
| **HEAD** | `be1b670` · branch `main` · 26 commits · **1 unpushed** (`origin/main` is at `6b1b18d`) |
| **Supersedes** | `docs/frontend/PROGRESS_SNAPSHOT_2026-09-17.md` (@ `6b1b18d`), `docs/BACKEND_STATE_AUDIT.md` (@ `225af01`), `docs/NEXT_TASK_PLAN.md` (@ `225af01`) |
| **Build status** | ⚠️ **FAILS TODAY** — 25 errors, all Testcontainers. Docker Engine not running on this machine. Environmental, not a code regression. See Part 4. |
| **Working tree** | One uncommitted change: `.gitignore` (scoping fix, see Part 1) |

**Measured this session** — 26 `@Entity` + 2 `@Embeddable` · 20 enums · 23 repositories · 35 `package-info.java` · 26 tables · 14 indexes (4 partial) · 18 CHECK constraints · 30 foreign keys · 2 migrations · 1 controller · 1 endpoint · 39 declared test methods (≈44 executed) · 7 ArchUnit rules · 20 markdown documents.

---

## Part 0 — How to use and maintain this document

### Who it is for

Fedi returning after a break, a future assistant session opening the repository cold, or a teammate who needs the truth in one place. It answers two questions: **what exists and how do I know**, and **what does not exist yet, named at sub-phase granularity**.

### What it guarantees

Every count in Part 3 was measured from source in this session, not copied. Every claim is marked as verified by execution, verified by reading, or **⚠️ UNVERIFIED** (Part 4).

### What it does NOT cover

Implementation detail for future work — that lives in `docs/user/ACTION_PLAN.md` for Phase 2 and will be written per-phase. It is not a design document, not a schema reference, and not a tutorial.

### Where it sits relative to every other document

| Document | Relationship |
|---|---|
| `docs/CALLVERSE_DB_SCHEMA.md` | **Defers to it.** The schema's source of truth; a teammate provisions the database by hand from it. Never fix a divergence locally — report it. |
| `CONTRIBUTING.md` | **Defers to it.** Binding engineering rules. |
| `docs/CLAUDE.md` | **Defers to it.** The no-AI-attribution rule. ⚠️ It was moved out of the repository root during the pause and is therefore no longer auto-loaded by tooling — see Part 7. |
| `docs/CALLVERSE_PROJECT_CONTEXT.md` | **Defers to it** for the *why*, the roadmap, the contracts and both juries' criteria. **Supersedes it** on frontend stack (says Angular) and Phase 1 status. |
| `docs/PROJECT_HANDOFF.md` (847 lines) | **Supersedes** on all status and counts. Still useful as narrative. Names Angular in 3 places. |
| `docs/BACKEND_STATE_AUDIT.md` | **Supersedes.** Its Phase 1 "PARTIAL — nothing has ever touched real Neon" was resolved on 2026-09-17. |
| `docs/NEXT_TASK_PLAN.md` | **Supersedes.** Its recommended Task A (verify real Neon) was done. |
| `docs/frontend/PROGRESS_SNAPSHOT_2026-09-17.md` | **Supersedes**, and **corrects it**: it marked sub-phase 2.0 complete; this document downgrades that to PARTIAL on new evidence (Part 1). |
| `docs/frontend/NEXTJS_PIVOT_AUDIT.md` | **Defers to it** for the BFF-vs-direct fork. Still current. |
| `docs/frontend/NEXTJS_SCAFFOLD_PROMPT.md` + `docs/NEXTJS_SCAFFOLD_PROMPT.md` | **Summarises.** ⚠️ Two near-duplicate copies exist; the newer 566-line one is untracked. See Part 7. |
| `docs/user/ACTION_PLAN.md` | **Defers to it** for Phase 2 implementation detail. **Corrects** its 2.0 "START HERE" marker. |
| `docs/user/JWT_AUTH_AUDIT.md`, `USER_MANAGEMENT_AUDIT.md`, `USER_TAXONOMY.md`, `OWNERSHIP_RULES.md` | **Summarises.** Their code-absence findings all re-confirmed today. `JWT_AUTH_AUDIT.md`'s "`@EnableMethodSecurity` ABSENT" is now false. |
| `README.md` | **Supersedes** on status. ⚠️ The most stale tracked document: names Angular twice and still says "Never verified against Neon". |
| `docs/PRESENTATION_2026-09-15*.md` | **Supersedes.** Both cite "20 commits"; there are 26. |
| `docs/superpowers/plans/*` | Historical process artefacts. Ignore. |

### Maintenance protocol

**Update this file when:** a phase or sub-phase changes status; a count changes; an open decision is resolved; a new contradiction between documents is found; or any pause longer than a few days ends.

**On a normal phase completion, touch:** Part 1 (lead + phase table), Part 2 (new subsection), Part 3 (re-measure), Part 6 (flip the sub-phase marker), Part 8 (add a row). Parts 0, 4, 5, 7 only if their content actually changed.

**Re-verification commands — run all of these before trusting this file again:**

```bash
git log --oneline -15 && git status --porcelain
git rev-parse --short HEAD          # compare against the header above
docker info >/dev/null 2>&1 && echo docker-up || echo docker-DOWN
./mvnw clean install                # requires Docker; 44 executed tests expected
docker compose config | grep SPRING_PROFILES_ACTIVE   # must not silently be 'dev'
find src/main/java/com/callverse/core/domain/entities -name '*.java' | wc -l
grep -c 'CREATE TABLE' src/main/resources/db/migration/V1__init.sql
grep -rn 'REVIEW:' src/main/resources/db/migration/
```

---

## Part 1 — State in one page

### What changed since the last audit — this is the lead

**Nothing was committed.** `HEAD` is `be1b670`, exactly where the 2026-09-17 audit left it. Seven days passed with zero commits. The project is paused, not drifting.

Three things did change, all outside git history:

1. **`CLAUDE.md` moved** from the repository root to `docs/CLAUDE.md`. It carries the binding no-AI-attribution rule and is no longer in the path assistant tooling auto-loads.
2. **`NEXTJS_SCAFFOLD_PROMPT.md` moved** from the root to `docs/`. It is untracked, so it exists on this machine only.
3. **`.gitignore` has an uncommitted one-line edit** changing the ignore rule `CLAUDE.md` → `docs/CLAUDE.md`, matching the move. Correct, and not yet committed.

### The facts that most change what happens next

1. ⚠️ **The "fail closed" fix does not work on the documented path.** `docker compose config` resolves `SPRING_PROFILES_ACTIVE: dev` — **verified by execution today**. Compose interpolates `${SPRING_PROFILES_ACTIVE:-prod}` from the root `.env`, and `.env.example:43` ships `SPRING_PROFILES_ACTIVE=dev`. Anyone following `cp .env.example .env` then `docker compose up` gets the `permitAll()` chain, not the deny-all one the comment promises. **Sub-phase 2.0 is therefore PARTIAL, not complete** — this corrects the 2026-09-17 snapshot.
2. **The build fails today**, 25 errors, all Testcontainers, because Docker Engine is not running. Not a regression. But it means the 44-test green result **cannot be re-verified in this session**.
3. **Authentication remains at absolute zero.** No login endpoint, no JWT filter, no `UserDetailsService`, no `PasswordEncoder`, zero `@PostMapping` anywhere, zero real role annotations. Re-confirmed from source today.
4. **Three declared dependencies are unused**: `jjwt`, `spring-boot-starter-websocket`, `spring-boot-starter-validation`. Present in `pom.xml`, imported nowhere.
5. **Four enum fields are mapped over columns with no CHECK constraint** — `Conversation.channel`, `Conversation.intent`, `Escalation.status`, `AgentDecision.agentType`. Bad data written by raw SQL round-trips at the database layer and throws on JPA read.
6. **Seven `-- REVIEW:` findings remain open in `V1__init.sql`**, each marked "Left as specified" — deliberate deference to the schema document, not oversight.
7. **The ESPRIT experiment matrix is 45 runs, not 60.** Confirmed: `V2__seed_reference.sql` seeds exactly three control strategies (`STATIC_FIFO`/BASELINE, `THRESHOLD`/HEURISTIC, `RL_PPO_V1`/RL). 3 loads × 3 strategies × 5 seeds = 45. `CALLVERSE_PROJECT_CONTEXT.md:360` still says ≈60.
8. **The schema and the migration agree exactly** — checked exhaustively, not sampled. No divergence in tables, columns, types, nullability or constraints.
9. **README is the most stale tracked document** — names Angular twice and still asserts "Never verified against Neon", which stopped being true on 2026-09-17.
10. **Git history is clean** — no AI attribution anywhere across all 26 commits, authors or committers; no `.env` ever committed; single author throughout.

### Phase table at a glance

| Phase | Status | One-line reason |
|---|---|---|
| **1 — Data foundation** | ✅ **COMPLETE** | Schema, migrations, entities, repositories, Testcontainers; Neon gap closed 2026-09-17 |
| **2 — Security and identity** | 🟡 **PARTIAL** | Only 2.0, and 2.0 itself is partial (see fact 1). 2.1–2.9 not started |
| **3 — `/internal` tool API** | ⛔ **NOT STARTED** | Zero of the seven tool endpoints exist |
| **4 — Business domain and real time** | ⛔ **NOT STARTED** | No queue, no state machine, no STOMP |
| **5 — Simulation orchestration** | ⛔ **NOT STARTED** | No run lifecycle, no telemetry ingestion |
| **6 — KPI engine and comparison** | ⛔ **NOT STARTED** | No `run_kpi` computation, no A/B endpoint |
| **7 — Hardening and delivery** | ⛔ **NOT STARTED** | — |

---

## Part 2 — What was built, phase by phase

### 2.1 Clean Architecture scaffold and the enforced dependency rule (2026-09-14, `9b9abff`…`d50fadb`)

**Delivered.** A single-module Maven project on Spring Boot 3.3.4 / Java 21, packaged as onion layers — `core` (domain + application) inside, `host` and `infrastructure` outside — with CQRS feature slices organised by feature, never by technical role. A health endpoint crosses every layer as a walking skeleton. A global exception handler produces one error envelope.

**Key decisions, and why:**
- *JPA annotations live on domain entities.* A pure domain plus parallel JPA entities plus mappers costs ~25 mapping classes for a gain invisible from outside. Accepted debt, documented, with ArchUnit confining framework contamination to `core.domain.entities`.
- *The dependency rule is a failing test, not a convention.* Conventions decay silently; a red build does not.
- *`failOnEmptyShould` left at ArchUnit's default of true*, and empirically probed — a rule matching zero classes fails the build rather than passing vacuously.

**Evidence.** `LayerDependencyTest.java` — 7 `@ArchTest` rules, passing today (they need no Docker).

**Unverified / known-narrow.** Rule 2 bans only `org.springframework.web`, `.security` and `.boot`. **`@Service`, `@Autowired`, `@Transactional` and Spring Data in `core` would all pass.** The "framework-free core" claim is only partially enforced. Rule 7 is a naming convention — a controller not named `*Controller` is invisible to it.

**Jury:** 3iL (clean architecture is an explicit criterion).

### 2.2 Migration-first schema and the persistence layer (2026-09-14, `c28016c`…`db5b978`)

**Delivered.** `V1__init.sql` creates 26 tables with 14 indexes, 18 CHECK constraints and 30 foreign keys. `V2__seed_reference.sql` seeds reference data and one account per role. 26 entities and 23 repositories map it. Testcontainers runs the suite against real PostgreSQL 16 with pgvector.

**Key decisions, and why:**
- *Flyway owns the schema; `ddl-auto: validate`, never `update`.* `update` works for one developer and destroys a team's database.
- *`docs/CALLVERSE_DB_SCHEMA.md` is truth, and divergences are reported, not fixed.* A teammate provisions by hand; a local fix produces a codebase and a database that disagree.
- *Seven `-- REVIEW:` findings left in place as "Left as specified"* rather than silently corrected — deference to the schema document, visible in the diff.
- *`kb_chunk.embedding vector(384)` deliberately unmapped* — Hibernate has no vector type; validate does not care.
- *Composite keys via `@EmbeddedId` + `@MapsId`*, all associations LAZY, no `@ManyToMany`, no `equals`/`hashCode` on entities.
- *Hikari pool capped at 8* — Neon's connection ceiling is shared across every instance, laptop and SQL console.

**Evidence.** 26 `CREATE TABLE`; 18 `SchemaValidationTest` methods; 7 adversarial `ConstraintEnforcementTest` methods, each asserting a *named* constraint fires.

**Unverified.** The whole persistence suite is **UNVERIFIED today** — Docker is down. Only 6 of the schema's constraints are probed. The pgvector column and its HNSW index are untested by design. Seeded reference data is checked by row count, not content.

**Jury:** both — 3iL reads CRUD depth, ESPRIT needs the experiment tables.

### 2.3 The self-audit and the user/JWT/ownership audits (2026-09-15, `225af01`, `c3de5a5`)

**Delivered.** `BACKEND_STATE_AUDIT.md` + `NEXT_TASK_PLAN.md`, then five documents under `docs/user/` specifying Phase 2: a 19-type identity taxonomy, a JWT audit, a user-management audit, an ownership-rule catalogue, and a ten-sub-phase action plan.

**Key findings that still hold today:** the ArchUnit Spring rule is narrower than advertised; the ESPRIT matrix is 45 runs not 60; three approver foreign keys are role-unconstrained, so a CUSTOMER is a schema-valid approver; ownership rules live in use-case bodies and are invisible from annotations.

**Unverified.** `BACKEND_STATE_AUDIT.md:231` says **17** ownership-blind repository methods; `OWNERSHIP_RULES.md:7` says **22**. The discrepancy is flagged inside the documents themselves and **has never been settled**. Neither number was re-derived today.

**Jury:** both — ownership rules are 3iL security; the HITL catalogue is ESPRIT.

### 2.4 Security hardening, the OpenAPI contract, and tooling (2026-09-17, `868afdb`…`be1b670`)

**Delivered.** `@EnableMethodSecurity` registered; the compose profile default inverted to `prod`; two false comments corrected empirically. The OpenAPI document gained metadata, two declared security schemes, and the error envelope attached to every operation via the exception handler. A `Makefile` wrapping the common commands. A one-line-commit-message rule. Three frontend-pivot documents.

**Key decisions, and why:**
- *`@EnableMethodSecurity` added while zero `@PreAuthorize` exist* — inert today, a working guard the moment the first one is written, rather than a silent no-op discovered later.
- *401/403 deliberately left out of the OpenAPI document* — Spring Security rejects before the dispatcher and returns an empty body, so documenting them would advertise a shape the API does not produce.
- *`bearerAuth` and `serviceKey` declared but not enforced* — without a registered scheme Swagger UI renders no Authorize button.
- *Maven invoked through an explicit interpreter in the Makefile* — Make on Windows bypasses `SHELL` for metacharacter-free lines and falls back to `cmd.exe`.

**Evidence.** `SecurityConfiguration.java:39`; `docker-compose.yml:43`; `OpenApiConfiguration.java:45-101`.

**⚠️ What remains unverified, and it matters.** The compose fix **does not achieve its goal**. `docker compose config` resolves `dev` because `.env.example:43` seeds it. Sub-phase 2.0 is PARTIAL. This corrects the 2026-09-17 snapshot, which called it complete.

**Jury:** 3iL.

---

## Part 3 — Measured inventory

| Artefact | **Measured today** | Previously claimed | Match? |
|---|---|---|---|
| Tables (`CREATE TABLE` in V1) | **26** | 26 | ✅ |
| Entity source files | **28** | 26 | ⚠️ 26 `@Entity` + 2 `@Embeddable` — both right, different definitions |
| `@Entity` classes | **26** | 26 | ✅ |
| `@Embeddable` composite-ID classes | **2** | 2 | ✅ |
| Domain enums | **20** | 20 | ✅ |
| Repository interfaces | **23** | 23 | ✅ |
| `package-info.java` (src total) | **35** (34 main + 1 test) | 35 | ✅ |
| Named indexes | **14** | 14 | ✅ |
| Partial indexes (WHERE) | **4** | 4 | ✅ |
| CHECK constraints | **18** | 18 | ✅ (a naive grep returns 22; four hits are inside `-- REVIEW:` comments) |
| Foreign keys | **30** | not previously claimed | — |
| Flyway migrations | **2** | 2 | ✅ |
| `@RestController` classes | **1** | 1 | ✅ (`@RestControllerAdvice` is not a controller) |
| HTTP endpoints | **1** | 1 | ✅ `GET /api/v1/health/status` |
| Declared test methods | **39** | 39 | ✅ |
| Executed test cases | **≈44** (estimate; one `@ParameterizedTest` × 6) | 44 | ✅ |
| ArchUnit rules | **7** | 7 | ✅ |
| Commits on `main` | **26** | 20 (audit/presentations), 25 (snapshot) | ❌ **both stale** |
| Markdown documents | **20** | not previously claimed | — |
| Control strategies seeded | **3** | 4 implied by "≈60 runs" | ❌ **matrix is 45, not 60** |
| Real `@PreAuthorize` annotations | **0** | 0 | ✅ (2 hits exist, both inside comments) |

**Findings, not footnotes:** the commit count is stale in four documents; the strategy count contradicts the experiment protocol; and the 17-vs-22 ownership-blind-method discrepancy remains unresolved in the source documents.

---

## Part 4 — What is verified, what is not

### Verified by execution, in this session

| Claim | Evidence |
|---|---|
| `HEAD` = `be1b670`, 26 commits, 1 unpushed, clean but for `.gitignore` | `git log`, `git status` |
| **The build FAILS today: 25 errors, all Testcontainers** | `./mvnw clean install` → `BUILD FAILURE`; `Could not find a valid Docker environment` |
| ArchUnit rules pass (7/7) and enum tests pass (12) | Same run — these need no Docker |
| **`docker compose config` resolves `SPRING_PROFILES_ACTIVE: dev`** | `docker compose config` — the single most important finding here |
| Docker Engine is not running on this machine | `docker info` → non-zero, after a Docker Desktop start attempt and ~4 minutes |
| Exactly 3 control strategies are seeded | `V2__seed_reference.sql` INSERT read in full |

### Verified by reading source, this session

Zero authentication of any kind. `@EnableMethodSecurity` present, zero real role annotations. CSRF disabled in both chains, and the "no cookie exists anywhere" justification still holds — one grep hit, and it is the comment itself. Nothing reads `jwt.secret`, so the documented fail-fast does not fire. One controller, one endpoint. WebSocket package contains only `package-info.java`, zero STOMP hits repo-wide. Schema document and `V1__init.sql` agree exactly (checked exhaustively). All associations LAZY, no `@ManyToMany`, no entity `equals`/`hashCode`. Git history clean of AI attribution; no `.env` ever committed.

### ⚠️ UNVERIFIED

| Claim | Why it could not be settled | What would settle it |
|---|---|---|
| **The 44-test suite passes** | Docker Engine down all session | Start Docker, `./mvnw clean install` |
| **Anything works against real Neon today** | Not attempted; the password was flagged for rotation on 2026-09-17 and may since have changed | `make api` and hit `/api/v1/health/status` |
| Whether the Neon branch still holds the applied schema | Same | Query `flyway_schema_history` |
| 17 vs 22 ownership-blind repository methods | Never re-derived by anyone; the documents disagree with each other | Count them once, record the number |
| Hibernate's numeric precision strictness vs `NUMERIC(p,s)` | Version-dependent behaviour, not probed | A deliberate mismatch test |
| Whether `pgcrypto` and `vector` exist on the target Neon branch | Requires a live connection | `SELECT * FROM pg_extension` |

### Has anything ever run against real Neon?

**Yes — once, on 2026-09-17.** The application booted against Neon with Flyway enabled and `ddl-auto: validate`, both migrations applied as real migrations, and Hibernate validated the entity model against the live schema. **This was not re-verified today**, and `README.md:346` still says "Never verified against Neon", which is now wrong.

---

## Part 5 — Open decisions awaiting you

**None of these is resolved here.**

| # | Question | Positions | Consequence | Recommendation | Silently pre-empted in code? |
|---|---|---|---|---|---|
| 1 | Customer self-registration, or ADMIN provisioning for all four roles? | A: all provisioned · B: customers self-register | A jury may ask why a customer portal has no sign-up | A (provisioned) — smaller surface | **No.** Zero auth code exists. |
| 2 | Stateless refresh, or a persisted revocable `refresh_token` table? | 1: persisted + revocable · 2: stateless rotation | Option 1 needs a `V3` migration **and**, if the token goes in an `HttpOnly` cookie, reopens CSRF in both chains | Decide before 2.4; batch any schema edit into one hand-provisioning request | **No.** But note the exposure window today is 1 hour with no revocation of any kind. |
| 3 | BFF versus direct browser-to-backend calls (frontend) | D: direct, CORS + in-memory JWT · B: Next.js proxies REST | STOMP must go direct either way, so B creates two trust boundaries and two credential shapes | **D (direct)** — one transport story | **No.** |
| 4 | Does supervisor approval *gate* an agent decision, or merely *record* it? | Human-in-the-loop vs human-on-the-loop | As designed, the table logs decisions already taken — that is human-*on*-the-loop, a weaker ESPRIT claim | Settle before the report is written | **⚠️ Partially — by the schema.** The table shape already leans toward recording. |
| 5 | How strong is the baseline router — strict FIFO or skill-matched FIFO? | Weak baseline is easy to beat and a jury will say so | The single most commonly failed ESPRIT item | Skill-matched | **⚠️ Partially.** `STATIC_FIFO` is seeded as "No skill preference" — that is the weak variant. |
| 6 | Does the Customer Advisor agent get a real `advisor` row with a `credit_limit`? | Blocks the credit-ceiling ownership rule | Cannot close that rule until decided | — | **No.** |
| 7 | Scheduled-job / `RULE` actor identity convention | Synthetic system principal vs explicit actor parameter | Must be decided before the first `@Scheduled` | — | **No.** |

**New fork surfaced by this audit (#8):** `.env.example` ships `SPRING_PROFILES_ACTIVE=dev`, which defeats the compose fail-closed default. Either ship it commented out (safe, one extra setup step) or keep it and accept that "fails closed" is untrue as written. **Not resolved here.**

---

## Part 6 — Phases not yet done

One line per sub-phase. Detail lives in `docs/user/ACTION_PLAN.md` (Phase 2) and will be written per-phase for the rest.

### Phase 2 — Security and identity 🟡 PARTIAL
*Objective: authenticate users, gate by role, and enforce ownership inside use cases.*

| # | Sub-phase | Status | Jury | Cuttable |
|---|---|---|---|---|
| 2.0 | Fail closed, and make role annotations work | 🟡 **PARTIAL** — `.env.example` still yields `dev` | 3iL | No |
| 2.1 | Password encoding and login | ⛔ | 3iL | No |
| 2.2 | The JWT filter and `SecurityContext` | ⛔ | 3iL | No |
| 2.3 | Auth error shape, and CORS | ⛔ | 3iL | No |
| 2.4 | Refresh tokens (resolves Decision 2) | ⛔ | 3iL | Last resort |
| 2.5 | Ownership rules inside use-case bodies | ⛔ | both | No |
| 2.6 | Human-in-the-loop authorization | ⛔ | ESPRIT | No |
| 2.7 | The `/internal` service key | ⛔ | both | No |
| 2.8 | WebSocket / STOMP authentication | ⛔ | 3iL | No |
| 2.9 | User provisioning endpoints (resolves Decision 1) | ⛔ | 3iL | Yes |

### Phase 3 — `/internal` tool API ⛔ NOT STARTED
*Objective: give the AI agents real data to act on, with business rules enforced backend-side.*

| # | Sub-phase | Jury | Cuttable |
|---|---|---|---|
| 3.1 | Service-key filter chain (shares 2.7) | both | No |
| 3.2 | Customer profile and contract lookup | both | No |
| 3.3 | Recent invoices lookup | both | No |
| 3.4 | Network incident status by zone | both | No |
| 3.5 | Knowledge-base search — text first, pgvector later | both | Partly (pgvector) |
| 3.6 | Ticket creation | both | No |
| 3.7 | Commercial credit with the ceiling enforced backend-side | both | No |
| 3.8 | Conversation escalation | both | No |

### Phase 4 — Business domain and real time ⛔ NOT STARTED
*Objective: make the relation center actually work, and push it live to the browser.*

| # | Sub-phase | Jury | Cuttable |
|---|---|---|---|
| 4.1 | Queue engine and skill routing | both | No |
| 4.2 | Priority score | 3iL | No |
| 4.3 | Conversation state machine enforced | 3iL | No |
| 4.4 | SLA engine | both | No |
| 4.5 | Escalation rules | 3iL | No |
| 4.6 | Business CRUD across the six modules | 3iL | Partly |
| 4.7 | WebSocket/STOMP transport on the five frozen topics | 3iL | No |
| 4.8 | Denormalised `wait_seconds` / `handle_seconds` / `sla_met` at state transition | both | No |

### Phase 5 — Simulation orchestration ⛔ NOT STARTED
*Objective: run experiments and capture their telemetry.*

| # | Sub-phase | Jury | Cuttable |
|---|---|---|---|
| 5.1 | Scenario CRUD | ESPRIT | No |
| 5.2 | Control-strategy CRUD | ESPRIT | No |
| 5.3 | Run lifecycle with the `uq_run` guarantee | ESPRIT | No |
| 5.4 | Launching the Python runner | ESPRIT | No |
| 5.5 | Batch telemetry ingestion via `JdbcTemplate`, never JPA | ESPRIT | No |
| 5.6 | `agent_decision` persistence — the XAI table | ESPRIT | No |
| 5.7 | Run progress pushed over WebSocket | both | Yes |
| 5.8 | Evaluation-only guard so training runs never reach the database | ESPRIT | No |

### Phase 6 — KPI engine and comparison ⛔ NOT STARTED
*Objective: produce the numbers the report and the jury will argue about.*

| # | Sub-phase | Jury | Cuttable |
|---|---|---|---|
| 6.1 | `run_kpi` computation | ESPRIT | No |
| 6.2 | Run A versus run B with deltas | ESPRIT | No |
| 6.3 | Multi-seed aggregation with mean **and standard deviation** | ESPRIT | No |
| 6.4 | CSV/JSON export | ESPRIT | Yes |
| 6.5 | Live supervision KPIs | 3iL | No |

### Phase 7 — Hardening and delivery ⛔ NOT STARTED
*Objective: survive the defence.*

| # | Sub-phase | Jury | Cuttable |
|---|---|---|---|
| 7.1 | Test coverage to the stated bar | both | Partly |
| 7.2 | Complete OpenAPI annotations | 3iL | No |
| 7.3 | README finalised (and de-Angular-ised) | both | No |
| 7.4 | `docker compose` end-to-end | 3iL | No |
| 7.5 | A frozen, populated Neon `demo` branch, pre-warmed | both | No |
| 7.6 | Audit log | 3iL | Yes |
| 7.7 | Two full demo rehearsals | both | No |

---

## Part 7 — Risks, ranked

| # | Risk | Impact | Jury endangered | Owner | Still holds? |
|---|---|---|---|---|---|
| 1 | **Seven days of zero commits.** Six of seven phases are unstarted and the two hardest (4, 5) are still ahead. | Schedule | both | Fedi | **New** |
| 2 | **"Fails closed" is untrue on the documented path.** A security claim in a comment that the configuration contradicts is worse than no claim — a jury that tests it finds an unauthenticated API. | Security + credibility | 3iL | Fedi | **New, verified today** |
| 3 | **A weak baseline.** `STATIC_FIFO` is seeded as "No skill preference" — the easy-to-beat variant. Named in the project's own context document as one of the two most commonly failed items. | Result invalidity | ESPRIT | Fedi + RL lot | Yes |
| 4 | **Results without variance.** Phase 6.3 is the only place mean ± standard deviation gets produced; if 6 slips, the numbers get computed by hand in the final week. | Result presentation | ESPRIT | Fedi | Yes |
| 5 | **Phase 3 is the widest-blast-radius dependency.** Until `/internal` is real, the AI service keeps mocking and contract mismatches surface too late. | Integration | both | Fedi | Yes |
| 6 | **The persistence suite is unverifiable without Docker**, and nothing is verified against Neon on any schedule. A Neon-specific failure (pooler breaking Flyway's advisory lock) is invisible to the suite. | Silent breakage | both | Fedi | Yes |
| 7 | **`docs/CLAUDE.md` left the auto-loaded path.** The no-AI-attribution rule is jury-relevant and now depends on memory rather than an always-loaded file. | Process | both | Fedi | **New** |
| 8 | **The core is only half framework-free.** ArchUnit bans three Spring subtrees; `@Service`/`@Autowired`/`@Transactional` in `core` pass. The 3iL "clean architecture, ArchUnit-enforced" claim is stronger than the rule. | Credibility | 3iL | Fedi | Yes |
| 9 | **Two near-duplicate scaffold prompts**, the newer untracked — it vanishes on a fresh clone. | Coordination | 3iL | Fedi | **New** |
| 10 | **Stale tracked documents**: README names Angular and denies the Neon connection; four documents cite 20 or 25 commits against an actual 26. | Credibility | both | Fedi | Yes |

---

## Part 8 — Update log

| Date | HEAD | What changed | Updated by |
|---|---|---|---|
| 2026-09-24 | `be1b670` | Document created. Full re-audit after a 7-day pause across six parallel dimensions. Found: zero commits in the gap; the compose fail-closed default defeated by `.env.example` (2.0 downgraded COMPLETE → PARTIAL); build failing on a Docker-down machine; three unused dependencies; commit count stale in four documents. All counts re-measured from source. | Audit session |

