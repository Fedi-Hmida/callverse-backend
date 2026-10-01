# CallVerse — Backend

CallVerse is a digital twin of a retail bank's customer relation center. Four autonomous agents operate
inside it — a Client Simulator, a Customer Advisor, a Workforce Manager driven by reinforcement
learning, and a Quality Analyst — and the platform runs in two modes on one codebase: **live**, with
a real person on the customer portal, and **simulation**, with thousands of synthetic customers used
to train the RL policy and to produce measured results against a baseline. This repository is the
**backend**: it owns the business domain (customers, accounts, cards, transactions, conversations,
tickets, advisors, skills), the queue engine and skill-based routing, the SLA and escalation rules, the
commercial-credit ceilings, security and roles, simulation-run orchestration, and the KPI engine.
The Next.js frontend and the Python/FastAPI service running the LangGraph agents live in their own
repositories. The AI-service integration is deliberately deferred until the frontend–backend–database
path is complete: the `/internal` tool API the agents called was withdrawn on 2026-09-30 and is kept
under the git tag `internal-tools-http-surface`.

One rule is worth stating up front, because it is the reason several design decisions here look
conservative: **the backend, never the agent, is the authority on business rules.** An agent that
wants to grant a commercial credit asks this service, and this service decides.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** (LTS) | Tested on Temurin 21.0.12. Java 22+ will not work: Lombok and Hibernate's Byte Buddy on Boot 3.3.4 do not support newer class-file versions. |
| Maven | none needed | Use the bundled wrapper (`./mvnw`), which fetches Maven 3.9.16 on first run. |
| PostgreSQL | **Neon**, remote | No local install. See *Database* below. |
| Docker | 24+ | **Required to run the test suite.** The persistence tests start a real PostgreSQL via Testcontainers; `./mvnw clean install` fails without a running Docker daemon. Also used by `docker compose` and for building the runtime image. |
| Node.js | 20+ | Only for the frontend's type generation. Not needed for the backend. |

Check your JDK before anything else — a wrong `JAVA_HOME` is the most common first-day failure:

```bash
java -version    # must report 21.x
```

---

## Setup

```bash
git clone <repository-url>
cd callverse-backend
cp .env.example .env      # then fill it in, see below
./mvnw clean install
```

### Database

CallVerse uses **Neon**, a serverless PostgreSQL. There is no local database and no Postgres service
in `docker-compose.yml`; see the comment at the top of that file for why.

To get your connection details:

1. Sign in to the Neon project (ask the team lead for access).
2. Create **your own branch** of the database rather than sharing `main`. Neon branches are
   copy-on-write and free, and they are what replaces the local container you were expecting.
3. From the connection details panel, copy the host into `.env`. Neon shows **two** endpoints for the
   same database and you need both:
   - the **pooled** endpoint, whose host contains `-pooler` → `DB_HOST`
   - the **direct** endpoint, the same host *without* `-pooler` → `DB_DIRECT_HOST`

The application runs through the pooled endpoint; Flyway runs migrations through the direct one.
That split is not optional — the reason is in the comments in `application.yml`, and getting it wrong
produces a hung deploy rather than an error message.

`JWT_SECRET` has no default, so the application will refuse to start without one. Generate it:

```bash
openssl rand -base64 48
```

**Never commit `.env`.** `.gitignore` covers `.env` and every `.env.*` variant, with `.env.example`
as the only tracked template. Verify with `git check-ignore -v .env` if you are unsure.

---

## Running

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev     # run locally
./mvnw test                                               # tests only
./mvnw clean install                                      # full build + tests
./mvnw spring-boot:build-image                            # container image, no Dockerfile needed
docker compose up                                         # backend only
docker compose --profile ai up                            # backend + Python AI service
```

Once running:

| | |
|---|---|
| Health (product) | <http://localhost:8080/api/v1/health/status> |
| Health (platform probe) | <http://localhost:8080/actuator/health> |
| Swagger UI | <http://localhost:8080/swagger-ui.html> *(dev profile)* |
| OpenAPI document | <http://localhost:8080/v3/api-docs> |

The `dev` profile enables SQL logging with bound parameters, opens every actuator endpoint, and
serves Swagger UI unauthenticated. **Never enable it in a deployed environment.**

---

## Architecture

Onion / Clean Architecture in a **single Maven module**. Dependencies point inward.

| Layer | Package | Responsibility |
|---|---|---|
| **Domain** | `core.domain` | The business itself: entities, enumerations, pure calculators, and the vocabulary of things the business forbids. Knows nothing about use cases. |
| **Application** | `core.application` | Use cases, one per thing the system can be asked to do, as CQRS feature slices. Declares the ports it needs; knows nothing about who implements them. |
| **Host** | `host` | Delivery: REST controllers, STOMP endpoints, request/response DTOs, and the translation of exceptions into the error envelope. |
| **Infrastructure** | `infrastructure` | Adapters: JPA repositories, the Python AI service client, scheduling, security, and the Spring configuration that wires framework-free use cases into beans. |

Two properties of this layout are load-bearing and easy to erode:

**The application layer is sliced by feature, not by technical role.** `features/conversation/commands`
and `features/conversation/queries`, never `services/` or `managers/`. This is the single biggest
reason the reference project this layout came from stayed readable at 13 controllers, 96 endpoints
and ~30 entities. Everything one feature needs is in one directory; nothing is in a package named
after a pattern.

**Use cases stay framework-free; infrastructure wires them.** A query handler is plain Java with a
constructor — no `@Service`, no `@Autowired` — and becomes a bean in `infrastructure.config`. See
`HealthFeatureConfiguration` for the pattern and the reasoning. This costs about five lines per
handler and buys three things: the dependency rule stays absolute rather than negotiable, handlers
are unit-testable with `new` and no application context, and the same handlers are callable by the
simulation runner and the scheduled SLA sweeps without going through HTTP.

### How the dependency rule is enforced

By a test that fails the build, not by discipline.

A multi-module build would let the compiler enforce these boundaries: if `core` cannot see `host` on
its classpath, importing it is impossible. A single module trades that guarantee for simplicity, and
`src/test/java/com/callverse/architecture/LayerDependencyTest.java` buys it back. Seven ArchUnit
rules:

1. `core` must not depend on `host` or `infrastructure`.
2. `core` must not depend on anything under `org.springframework` — no `@Service`, `@Component`, `@Autowired` or `@Transactional`.
3. `core.domain` must not depend on `core.application`.
4. Only `core.domain.entities` may depend on `jakarta.persistence`.
5. `host` must not depend on `infrastructure.persistence` — controllers talk to handlers, never to
   repositories.
6. The layers must be free of cycles.
7. Classes named `*Controller` live only in `host.api.controllers`.

These rules were verified to actually fire before being trusted: annotating a use-case handler with
`@RestController` and referencing `EntityManager` from a domain calculator each produced a targeted
failure and a red build. ArchUnit's `failOnEmptyShould` is left at its default, so a rule that matched
no classes would fail rather than pass silently.

If a rule ever blocks legitimate work, change the boundary deliberately and change the rule. Do not
add an exclusion to get the build green.

---

## Architectural decision: JPA annotations in domain entities

This is the decision most likely to be challenged, so it is written down rather than left to be
discovered.

**Domain entities in `core.domain.entities` carry JPA annotations.** `@Entity`, `@Table`, `@Column`
appear on domain types. A purist Clean Architecture would forbid this.

**The alternative we rejected.** The strict approach is a framework-free domain, a parallel set of
JPA entities in `infrastructure.persistence`, and a mapper between them. For ~30 entities that is
roughly 25 extra mapping classes plus their tests, all of which must be updated in lockstep every
time a field is added.

**Why we accepted the compromise.** The gain from the strict version is the ability to swap the
persistence technology without touching the domain, and the purity of a domain with zero framework
imports. We are not going to swap PostgreSQL — the schema, Flyway, and the coming pgvector work all
assume it. So the practical benefit is invisible from outside the system, while the cost is
permanent and paid on every change. The reference project this architecture comes from ran this way
to ~30 entities without the domain becoming unreadable.

**What we pay for it.** The domain is not framework-pure, and a future migration away from JPA would
be genuinely expensive. We accept that.

**How the compromise is bounded.** Rule 4 above. `jakarta.persistence` is permitted in
`core.domain.entities` and nowhere else under `core`. Without that boundary, "the domain already
imports JPA" becomes the argument for a `TypedQuery` inside a use case six months from now. The
concession is one package wide, and the build enforces it.

---

## API conventions

| | |
|---|---|
| **Routes** | `/api/v1/...` for the public API. The AI service's tool API (`/internal/...`) is withdrawn until the AI-integration phase re-agrees its prefix and casing. Version in the path, plural nouns, no verbs. |
| **Identifiers** | UUIDs are exposed publicly. Database sequence IDs never appear in a payload or a URL — they leak row counts and are guessable. |
| **Dates** | ISO-8601, always UTC, always with the `Z` suffix. The backend does not do timezones; the frontend formats for the user. A KPI compared across two simulation runs must not be measuring a daylight-saving transition. |
| **Errors** | Every failure returns the same envelope: `timestamp`, `status`, `code`, `message`, `path`. Clients branch on `code`; `message` is for humans and may be reworded or translated. |
| **Pagination** | Request `?page=0&size=20&sort=createdAt,desc`. Respond `{ "content": [...], "page": { "number", "size", "totalElements", "totalPages" } }`. `size` is capped server-side. |
| **Roles** | `CUSTOMER`, `ADVISOR`, `SUPERVISOR`, `ADMIN`. Fixed by the API contract and always English, even where the product documentation uses French domain vocabulary. |

### Frontend type generation

The Next.js client generates its TypeScript types from the OpenAPI document rather than hand-writing
them, which makes a backend contract change a compile error in the frontend instead of a runtime
surprise. With the backend running:

```bash
npx openapi-typescript http://localhost:8080/v3/api-docs -o src/shared/api-client/generated/callverse-api.d.ts
```

Because of this, `host.api.dto.response` is a published contract. Renaming a field there is a
breaking change for the frontend.

---

## The persistence layer

The schema's source of truth is **[`docs/CALLVERSE_DB_SCHEMA.md`](docs/CALLVERSE_DB_SCHEMA.md)**,
shared with whoever provisions the Neon database. `V1__init.sql` and every entity match it exactly.
If you believe something in it is wrong, raise it there — do not fix it in one place only, because a
divergence between the migration and the provisioned database is silent and expensive.

The rest of `docs/` is deliberately untracked (see `.gitignore`); only the schema reference is
versioned, so that schema changes show up in diffs.

### Entity map — 27 tables, 27 entities, 23 repositories

| Block | Entities | Aggregate roots (have a repository) |
|---|---|---|
| 1 Identity | `AppUser` | `AppUser` |
| 2 Customer | `Customer`, `BankingProduct`, `Account`, `Card`, `BankTransaction` | `Customer`, `Card`, `BankTransaction` |
| 3 Resources | `Skill`, `Advisor`, `AdvisorSkill` | `Skill`, `Advisor` |
| 4 Interaction | `Conversation`, `Message`, `Ticket`, `CommercialCredit`, `Escalation` | all five |
| 5 Knowledge | `KbArticle`, `KbChunk` | both |
| 6 Control | `SlaPolicy`, `RoutingRule`, `ServiceIncident` | all three |
| 7 Experiment | `ControlStrategy`, `Scenario`, `SimulationRun`, `RunKpi`, `MetricSample`, `AgentDecision` | all but `RunKpi` |
| 8 Quality | `QualityCriterion`, `QualityEvaluation` | both |

Three entities deliberately have **no** repository, because they live inside another aggregate and
are reached through its root: `Account` (via `Customer.getAccounts()`), `AdvisorSkill` (via
`Advisor.getSkills()`), and `RunKpi` (via `SimulationRun.getKpi()`, sharing its primary key).
`BankingProduct` has none: no use case reads it on its own. `Card` has one since card blocking,
which locks the row so two simultaneous blocks resolve to one.

**The domain is retail banking.** It was telecom until 2026-09-30; `V3__banking_domain.sql` dropped
the telecom tables (`plan`, `contract`, `invoice`, `network_incident`), renamed `customer.zone` to
`region`, and created the banking ones. `V1` and `V2` are untouched because they are applied on
Neon. **No card number, CVV or PIN is stored anywhere**: a card keeps its last four digits only, and
the database refuses more.

`MetricSampleRepository` is the odd one out: it extends Spring Data's bare `Repository`, **not**
`JpaRepository`, so `save` and `saveAll` do not exist on the type. Writes to `metric_sample` go
through the `MetricSampleBatchWriter` port and batched JDBC — roughly 650,000 rows across the full
experimental matrix, which JPA row-by-row would turn into that many round trips to Neon. The guard
is a compile error rather than a comment on purpose.

### Mapping conventions

Decided once and applied to all 27 entities. Inconsistency across that many classes is worse than a
uniform but imperfect choice.

| Decision | Choice | Why |
|---|---|---|
| UUID primary keys | `@GeneratedValue(strategy = GenerationType.UUID)` | Hibernate-side generation needs no read-back; a DB-generated default forces a `RETURNING` fetch per row, defeating batching and costing a Neon round trip. The SQL `DEFAULT gen_random_uuid()` stays for direct SQL such as the seed. |
| `BIGSERIAL` keys | `GenerationType.IDENTITY` | `message`, `kb_chunk`, `agent_decision` |
| `TIMESTAMPTZ` | `java.time.Instant` | The column stores a UTC instant and discards the offset, so `OffsetDateTime` would advertise information it does not carry. |
| `DATE` | `java.time.LocalDate` | Calendar dates, not instants — an account opens on an agreed day, not at a moment. |
| `NUMERIC` | `BigDecimal` with explicit `precision`/`scale` | Never `double`. These are money and ratios that get compared and summed. |
| JSONB | `@JdbcTypeCode(SqlTypes.JSON)` on `JsonNode` | Handles object- *and* array-shaped columns uniformly with no POJO per column. Proven to round-trip before 11 columns depended on it. |
| `TEXT[]` | `String[]` + `SqlTypes.ARRAY` + `columnDefinition` | Tags are read with their article, never queried across articles, so a join table would buy nothing. |
| `vector(384)` | **field omitted entirely** | Hibernate has no pgvector type. Validation is directional — it checks mapped attributes exist, not that every column is mapped — so the column is simply invisible to JPA. A field that existed but silently ignored writes would be a trap. Retrieval will use native SQL. |
| `equals`/`hashCode` | **None on entities**; present on the two `@Embeddable` id classes | Associations are unidirectional by default, so entities never enter a Hibernate-managed hash collection, and an id-based `equals` becomes a trap the first time a detached instance meets a managed one. Composite id classes have no choice: JPA uses them as map keys. |
| Lombok | `@Getter @Setter @NoArgsConstructor`, `@Setter(AccessLevel.NONE)` on ids | Never `@Data`, never `@EqualsAndHashCode` on an entity. |
| Fetching | `FetchType.LAZY` on every association | Including `@ManyToOne`, whose JPA default is EAGER. Stated explicitly on each one, because the default is the trap. |
| `conversation.run_id` | Plain `UUID`, no association, no FK | Matches the schema document literally, and keeps the business universe from traversing into the experiment universe. |

### Running the persistence tests

```bash
./mvnw test -Dtest=SchemaValidationTest       # 18 tests, all eight blocks
./mvnw test -Dtest=ConstraintEnforcementTest  # 7 tests, constraints actually rejecting
```

Both need Docker and nothing else — a `pgvector/pgvector:pg16` container is started automatically,
Flyway applies `V1` and `V2` into it, and `@DynamicPropertySource` supplies the datasource and the
`jwt.secret` that `application.yml` deliberately leaves without a default. **No Neon credentials and
no `.env` are involved.**

The stock `postgres:16` image will not work: `V1__init.sql` runs `CREATE EXTENSION vector` and builds
an HNSW index, so the image is part of the contract.

### Seeded development accounts

`V2__seed_reference.sql` creates one account per role. All four share the development password:

```
CallVerse!Dev2026
```

Emails are `customer@`, `advisor@`, `supervisor@` and `admin@callverse.local`. Only BCrypt hashes are
committed; the plaintext lives here rather than in a SQL comment, because a password in a comment is
a password in the repository. **These accounts are for local development and demonstration only.**

## Database migrations

Flyway is the **only** authority on the schema. `spring.jpa.hibernate.ddl-auto` is `validate` and must
never be `update` or `create`: `update` works for one developer and destroys a team, because it
silently diverges each developer's database from the migration history.

Migrations live in `src/main/resources/db/migration`, named `V<n>__<description>.sql`. The directory
is currently empty — `V1__init.sql` is a separate task — and holds a `.gitkeep` only because git
cannot track an empty directory. Delete that file when the first migration lands.

Never edit a migration that has been merged. Add a new one.

---

## Contributing

**Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before your first change.** It carries the rules that
are not obvious from the code: the schema document is the single source of truth, Flyway owns the
schema, the dependency rule is changed deliberately rather than excluded, and no secret enters the
repository.

**Commits** follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`,
`chore:`, `docs:`, `test:`, `refactor:`. Commit in logical increments — the build history is
documentation, and one lump "initial commit" throws it away.

**Branches** are `feature/<slice>` off `main`, named for the feature slice they touch, e.g.
`feature/conversation-routing`. `main` stays releasable.

**Before pushing:**

```bash
./mvnw clean install
```

This runs the architecture tests. If `LayerDependencyTest` fails, you have crossed a layer boundary —
read the failure message, which names the class, the line, and the reason the rule exists.

---

## Project status

Security, authentication and the complete persistence layer. The full-stack path (frontend, backend,
database) comes first; the AI-service integration comes after it.

**Exists:** the layer structure and its ArchUnit enforcement; configuration profiles; the error
envelope; JWT (HS512) login and authentication with a permissive `dev` chain and deny-by-default
elsewhere; eleven operations, listed under *Advisor workspace* below; 27 domain enums
including the conversation state machine; three migrations — `V1__init.sql`, `V2__seed_reference.sql`
and `V3__banking_domain.sql` — giving 27 tables; 27 entities, 23 repositories, and the
`MetricSampleBatchWriter` port; and a Testcontainers suite proving migrations and entities agree.

**Advisor workspace** (2026-10-01): what an advisor needs to handle a banking call. Every route
behind a login is gated twice — a filter-chain rule and `@PreAuthorize` — and tested for success
and refusal by role. Successful writes (card block, ticket, escalation) log an `AUDIT` line naming
the user, until the schema has actor columns.

| Operation | Route | Who |
|---|---|---|
| `login` | `POST /api/v1/auth/login` | anyone |
| `getCurrentUser` | `GET /api/v1/auth/me` | signed in |
| `getHealthStatus` | `GET /api/v1/health/status` | anyone |
| `findCustomerByReference` | `GET /api/v1/customers?externalRef=` | staff |
| `getCustomer` | `GET /api/v1/customers/{id}` | staff |
| `listCustomerTransactions` | `GET /api/v1/customers/{id}/transactions?count=` | staff |
| `listActiveServiceIncidents` | `GET /api/v1/service-incidents?region=` | signed in |
| `searchKnowledgeArticles` | `GET /api/v1/kb/articles?q=&limit=` | staff |
| `openTicket` | `POST /api/v1/tickets` | advisor, supervisor |
| `blockCard` | `POST /api/v1/cards/{id}/block` | advisor, supervisor |
| `escalateConversation` | `POST /api/v1/conversations/{id}/escalations` | advisor |

*Staff* is ADVISOR, SUPERVISOR or ADMIN. **Staff can read any customer** until ownership rules land
(they need a `UNIQUE` constraint on `customer.user_id`); this is documented on each route, not an
oversight. Account IBANs are masked, and so is any IBAN inside a transaction's label or
counterparty; free text elsewhere (ticket descriptions, KB content) is not scanned. The public
health route reports the build version and active profile, and nothing else.

The contract is committed as `openapi.yaml` and pinned by `OpenApiContractTest`: a changed route,
operation id or field fails the build until `make openapi` regenerates the file and the diff is
committed. A demo dataset for the call scenario loads in `dev` only, and only with
`CALLVERSE_DEMODATA_ENABLED=true` (`callverse.demo-data.enabled`); it is never a migration.

**Does not exist yet:** routing, SLA and priority-scoring logic (the state machine is declared on
`ConversationStatus` but only escalation consults it); the conversation lifecycle and message routes;
the STOMP topics; customer self-service; simulation-run and KPI routes; quality routes; the `MetricSampleBatchWriter` implementation;
the RAG embedding pipeline; and every call to or from the AI service.

**Neon:** the application booted against Neon on 2026-09-17 and again on 2026-09-24, with Flyway at
version 2 and Hibernate validating the entity model against the live schema. `V3` (the banking
domain) is applied on Neon and was verified on 2026-10-01: Flyway validated three migrations and the
application started. That proves tables,
columns and types — not constraints or indexes, which `validate` does not check. The pooled/direct
endpoint split under load, serverless cold starts, and whether the Hikari cap of 8 suits the real
connection ceiling all remain unproven.

The health slice is temporary and should be deleted once real features land — it is one directory per
layer.
