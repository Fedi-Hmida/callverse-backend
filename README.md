# CallVerse — Backend

CallVerse is a digital twin of a telecom customer relation center. Four autonomous agents operate
inside it — a Client Simulator, a Customer Advisor, a Workforce Manager driven by reinforcement
learning, and a Quality Analyst — and the platform runs in two modes on one codebase: **live**, with
a real person on the customer portal, and **simulation**, with thousands of synthetic customers used
to train the RL policy and to produce measured results against a baseline. This repository is the
**backend**: it owns the business domain (customers, contracts, invoices, conversations, tickets,
advisors, skills), the queue engine and skill-based routing, the SLA and escalation rules, the
commercial-credit ceilings, security and roles, simulation-run orchestration, the KPI engine, and the
`/internal` tool API the Python AI service calls. The Angular frontend and the Python/FastAPI service
running the LangGraph agents live in their own repositories.

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
| Docker | 24+ | Only for `docker compose` and for building the runtime image. Not needed to run tests. |
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
2. `core` must not depend on `org.springframework.web`, `.security`, or `.boot`.
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
| **Routes** | `/api/v1/...` for the public API; `/internal/...` for the AI service's tool API. Version in the path, plural nouns, no verbs. |
| **Identifiers** | UUIDs are exposed publicly. Database sequence IDs never appear in a payload or a URL — they leak row counts and are guessable. |
| **Dates** | ISO-8601, always UTC, always with the `Z` suffix. The backend does not do timezones; the frontend formats for the user. A KPI compared across two simulation runs must not be measuring a daylight-saving transition. |
| **Errors** | Every failure returns the same envelope: `timestamp`, `status`, `code`, `message`, `path`. Clients branch on `code`; `message` is for humans and may be reworded or translated. |
| **Pagination** | Request `?page=0&size=20&sort=createdAt,desc`. Respond `{ "content": [...], "page": { "number", "size", "totalElements", "totalPages" } }`. `size` is capped server-side. |
| **Roles** | `CUSTOMER`, `ADVISOR`, `SUPERVISOR`, `ADMIN`. Fixed by the API contract and always English, even where the product documentation uses French domain vocabulary. |

### Frontend type generation

The Angular client generates its TypeScript types from the OpenAPI document rather than hand-writing
them, which makes a backend contract change a compile error in the frontend instead of a runtime
surprise. With the backend running:

```bash
npx openapi-typescript http://localhost:8080/v3/api-docs -o src/app/core/api/callverse-api.d.ts
```

Because of this, `host.api.dto.response` is a published contract. Renaming a field there is a
breaking change for the frontend.

---

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

Scaffold with a walking skeleton. What exists: the layer structure, the build, configuration
profiles, the error envelope, the enforced dependency rule, and one thin vertical slice
(`GET /api/v1/health/status`) that crosses every layer to prove the wiring.

What does not exist yet, and is deliberately out of scope for this pass: entities and the database
schema (`V1__init.sql`), the routing and SLA engines, the JWT implementation
(`SecurityConfiguration` is a documented skeleton with a permissive **dev-only** chain and a
deny-by-default chain everywhere else), and the `/internal` tool endpoints.

The health slice is temporary and should be deleted once real features land — it is one directory per
layer.
