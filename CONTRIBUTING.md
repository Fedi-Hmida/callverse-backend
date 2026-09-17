# Contributing to CallVerse backend

These are the rules that hold this codebase together. Most of them exist because the alternative was
tried somewhere and hurt. Each says *why*, so you can tell when a rule genuinely does not apply
rather than guessing.

Read [`README.md`](README.md) first for setup and architecture. This document is the rules.

---

## 1. The schema document is the single source of truth

[`docs/CALLVERSE_DB_SCHEMA.md`](docs/CALLVERSE_DB_SCHEMA.md) is authoritative. `V1__init.sql` and
every JPA entity match it exactly: same table names, column names, types, nullability, constraints
and index names.

**The Neon database is provisioned from that same document by hand.** So a change made on one side
only does not fail loudly — it produces a database and a codebase that disagree, and you find out
when something breaks in a way that points nowhere near the cause.

If something in the document looks wrong, **say so and leave it alone.** Report it, get it changed
in the document, and let both sides follow. Six such findings are already recorded as `-- REVIEW:`
comments in `V1__init.sql`; they are deliberately unfixed for exactly this reason.

The rest of `docs/` is intentionally untracked. Only the schema reference is versioned, so that
schema changes show up in diffs and scratch notes do not.

---

## 2. Flyway owns the schema

`spring.jpa.hibernate.ddl-auto` is `validate`. **Never set it to `update` or `create`** — not
permanently, not temporarily, not "just to see what Hibernate would generate".

`update` works perfectly for one developer and quietly destroys a team: each developer's database
drifts from the migration history in a different direction, and nothing reports it until a deployment
hits a schema nobody has actually got.

Migrations live in `src/main/resources/db/migration`, named `V<n>__<description>.sql`.

**Never edit a migration that has been merged.** Add a new one. An edited migration has already run
on someone's database and will not run again there.

---

## 3. The dependency rule is enforced, not encouraged

`core` must not depend on `host`, on `infrastructure`, or on Spring. `LayerDependencyTest` fails the
build when it does, and those rules were verified to actually fire before being trusted.

If a rule blocks work you believe is legitimate, **change the boundary deliberately and change the
rule.** Do not add an exclusion to get the build green. An exclusion is how an architecture stops
being real: the first one always has a good reason, and so does every one after it.

Use cases stay framework-free plain Java with constructor injection — no `@Service`, no
`@Autowired` — and `infrastructure.config` wires them into beans. See `HealthFeatureConfiguration`
for the pattern and the full reasoning. It costs about five lines per handler and buys three things:
the dependency rule stays absolute rather than negotiable, handlers are unit-testable with `new` and
no application context, and the same handlers stay callable by the simulation runner and the
scheduled sweeps without going through HTTP.

The application layer is sliced **by feature**, never by technical role. There is no `services/` or
`managers/` package and there should never be one.

---

## 4. No secret enters this repository

Not a Neon password, not a JWT secret, not a connection string with credentials. `application.yml`
uses `${VAR}` only; `.env.example` carries variable names and placeholders.

Before committing anything that touches configuration:

```bash
git check-ignore -v .env
```

The BCrypt hashes in `V2__seed_reference.sql` are the one deliberate exception. They hash a
documented development password whose plaintext lives in the README, never in a SQL comment — a
password in a comment is a password in the repository.

---

## 5. Git history is part of the deliverable

This is coursework assessed by a jury, and the commit history is read.

- **Conventional commits**: `feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`.
- **Commit in logical increments.** One lump "initial commit" throws away the build history an
  examiner will look at.
- **One line. No body.** A message is a single sentence naming what changed, under about 72
  characters: `fix(security): fail closed on the profile default`. Not a paragraph, not a
  bulleted summary of the diff.
- **Reasoning goes in the code, not the message.** A commit body is read once, by whoever runs
  `git log` that week, and never found again. The same explanation as a comment next to the
  surprising line, or in `docs/`, is read by everyone who touches it afterwards. If a change
  genuinely needs a paragraph to justify it, that paragraph belongs where the change is.
- **No automated tooling credits** in commit messages, pull request descriptions or tags. Authorship
  of this work is the student's, and generated attribution in the history misrepresents the
  submission.
- Branches are `feature/<slice>` off `main`, named for the feature slice they touch, e.g.
  `feature/conversation-routing`. `main` stays releasable.

---

## 6. Before you push

```bash
./mvnw clean install
```

**This requires a running Docker daemon.** The persistence tests start a real PostgreSQL through
Testcontainers; there is no in-memory substitute, because the schema uses JSONB, `TEXT[]`, partial
indexes and `pgvector`, and agreement with H2 would verify a database we do not deploy.

If `LayerDependencyTest` fails you have crossed a layer boundary — read the failure, which names the
class, the line, and the reason the rule exists. If `SchemaValidationTest` fails, an entity and the
migration disagree.

---

## 7. Language

English for code, identifiers, comments and documentation. French appears only in user-facing data,
such as skill labels and quality-criterion labels. Role names are fixed by the API contract and stay
English: `CUSTOMER`, `ADVISOR`, `SUPERVISOR`, `ADMIN`.
