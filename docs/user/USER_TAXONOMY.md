# CallVerse — User & Identity Taxonomy

| | |
|---|---|
| **Date** | 2026-09-15 |
| **Commit** | `225af017` on `main` (working tree has 3 javadoc-only modifications — see note) |
| **Method** | Verified against source. Every claim carries `file:line` and a CONFIRMED / CORRECTED / ⚠️ UNVERIFIED tag. |
| **Headline** | **The system has 4 roles but 19 distinct identity types.** Only 4 can ever authenticate, and **today exactly 0 can** — no authentication code exists. |

> **Working-tree note.** `AppUser.java`, `Contract.java` and `CommercialCredit.java` have uncommitted **javadoc-only** deletions (57 lines removed, no functional change). One matters here: `AppUser`'s class javadoc contained the only in-code statement of the simulated-actor rule — *"Simulated customers and simulated advisors have no account at all, which is why `customer.user_id` and `advisor.user_id` are both nullable."* The rule still holds (schema + test enforce it) but its written statement on the identity root class is gone. **Recommend restoring that paragraph.**

---

## 1. Summary — 19 identity types

| # | Identity type | Can authenticate **today** | Can authenticate **by design** | How it exists today | Lifecycle |
|---|---|---|---|---|---|
| 1 | **CUSTOMER** (`app_user.role`) | **No** | Yes (P2) | `V2:88-90` seed only | insert / `active=false` (unenforced) / delete **blocked** if it ever approved anything |
| 2 | **ADVISOR** | **No** | Yes (P2) | `V2:91-93` seed only | same; no `advisor.active` exists |
| 3 | **SUPERVISOR** | **No** | Yes (P2) | `V2:94-96` seed only | same; delete **blocked** by approver FKs |
| 4 | **ADMIN** | **No** | Yes (P2) | `V2:97-99` seed only | same |
| 5 | **Simulated customer** | **No** | **Never** | No code generates them yet | insert with `user_id NULL, is_simulated=true`; **no deactivation flag**; delete cascades everything |
| 6 | **Simulated advisor** | **No** | **Never** | No code generates them yet | deactivate = `status=OFFLINE`; delete **blocked** by `granted_by` |
| 7 | **AI / Python service** (service key) | **No** | Yes (P3) | **Does not exist** — no endpoint, no key, no property | nothing to create |
| 8 | **The four AI agents** (`AgentType`) | **No** | No — a data label | enum only; no row written by any code | n/a |
| 9 | **`RULE` automated actor** | **No** | No — label only | enum constant only; **no scheduler exists** | n/a |
| 10 | **`SYSTEM` message sender** | **No** | No — label only | enum constant only; no code emits one | n/a |
| 11 | **Unauthenticated caller** | **Yes, trivially** | health probe only | nothing required | **this is the default identity today** |
| 12 | **Orphaned profile** (`user_id` NULLed) | No | No | produced automatically by `ON DELETE SET NULL` | emergent; **indistinguishable from simulated by `user_id` alone** |
| 13 | **Human quality annotator** | n/a — **unattributed** | n/a | no FK to `app_user` exists | anonymous forever |
| 14 | **Credit approver** (`commercial_credit.approved_by`) | inherits 1–4 | Yes | any `app_user` — **role unconstrained** | §4 |
| 15 | **Decision approver** (`agent_decision.approved_by`) | inherits 1–4 | Yes | any `app_user` — **role unconstrained** | §4 |
| 16 | **Escalation resolver** (`escalation.resolved_by`) | inherits 1–4 | Yes | any `app_user` — **role unconstrained** | §4 |
| 17 | **Credit grantor** (`commercial_credit.granted_by`) | **No** — points at `advisor`, not `app_user` | No | any advisor row, **simulated included** | §5 |
| 18 | **KB chunking/embedding job** | No | No | **does not exist** | n/a |
| 19 | **Database / migration identity** | **Yes — the only live credential today** | Yes | `.env` (`DB_USERNAME`) | Neon-side, outside this repo |

---

## 2. The four human roles — CONFIRMED

**Enum ⟷ CHECK, character for character and in order — CONFIRMED.**

| SQL `V1:39-40` | Java `UserRole.java:11-16` |
|---|---|
| `CHECK (role IN ('CUSTOMER','ADVISOR','SUPERVISOR','ADMIN'))` | `CUSTOMER, ADVISOR, SUPERVISOR, ADMIN` |

`@Enumerated(EnumType.STRING)` at `AppUser.java:44`, `length=20` both sides. The schema document agrees (`CALLVERSE_DB_SCHEMA.md:474`).

⚠️ **Gap:** `SchemaValidationTest.java:640-647` asserts a seeded account exists per role, but **nothing tests that the enum matches the CHECK constraint.** A drift between them would not be caught.

**How they exist today — CONFIRMED:** only `V2__seed_reference.sql:87-100`, four BCrypt accounts guarded `ON CONFLICT (email) DO NOTHING`.

**CORRECTED — `SecurityConfiguration.java:22`** plans *"a `UserDetailsService` backed by the **advisor and customer tables**"*. That is wrong. The authentication root is `app_user` (`AppUserRepository.java:9-11`); `customer` and `advisor` are *profiles* whose `user_id` is nullable and non-unique. Building a `UserDetailsService` on those tables would make simulated rows authenticable. **Fix the comment before someone implements it.**

**Lifecycle — verified:**
- **Create:** `INSERT` only. No code path (see `USER_MANAGEMENT_AUDIT.md`).
- **Deactivate:** `active BOOLEAN NOT NULL DEFAULT TRUE` (`V1:41`, `AppUser.java:49-50`), with partial index `idx_user_role ... WHERE active` (`V1:46`) commented *"inactive users are never routed to or authenticated"*. ⚠️ **That comment is an aspiration.** `AppUserRepository.findByEmailIgnoreCase` (`:17`) does **not** filter `active`, and no login code exists to filter it.
- **Delete:** partially **blocked at the database level**. `customer.user_id` and `advisor.user_id` are `ON DELETE SET NULL` (`V1:58`, `:130`), but `commercial_credit.approved_by` (`V1:230`), `escalation.resolved_by` (`V1:241`) and `agent_decision.approved_by` (`V1:416`) have **no `ON DELETE` clause** ⇒ `NO ACTION` ⇒ **refusal**. **Any account that has ever approved anything cannot be hard-deleted.** Correct for audit; must be a documented decision.

---

## 3. Simulated actors — they can never authenticate

**CONFIRMED on both sides:**

| | SQL | Entity |
|---|---|---|
| customer | `V1:58` — `user_id UUID REFERENCES app_user(id) ON DELETE SET NULL` (no `NOT NULL`) | `Customer.java:49-51` — no `optional=false`, no `nullable=false` |
| advisor | `V1:130` — identical | `Advisor.java:51-53` — identical |

Contrast a genuinely mandatory association in the same package: `CommercialCredit.java:34-35` uses `@ManyToOne(optional = false)`. The absence on `user` is deliberate.

**Nothing creates an `app_user` for a simulated actor — CONFIRMED.** The only `INSERT INTO app_user` in the repository is `V2:87-99`, four named human accounts. Evidence in code: `Customer.java:48` *"Null for a simulated customer, who has no way to log in"*; `Advisor.java:50` likewise; `V1:53-55` explains why. Executable proof: `SchemaValidationTest.java:154-171` persists a simulated customer and asserts `getUser()` is null.

**Structurally impossible today:** there is no credential column on `customer` or `advisor` — `password_hash` exists only on `app_user` (`V1:36`).

> ⚠️ **But the invariant is NOT enforced.** `is_simulated = true` **and** a non-null `user_id` are simultaneously representable — there is **no `CHECK (NOT is_simulated OR user_id IS NULL)`** on either table (`V1:56-69`, `V1:128-137`). **Recommend adding that CHECK before any simulation code is written.** One line makes a convention structural.

**Lifecycle:** no `active` flag exists on `customer` or `advisor` at all. An advisor's only off-switch is `status='OFFLINE'`; **a customer cannot be deactivated.** Deleting a customer cascades destructively across `contract`, `conversation`, `ticket`, `commercial_credit` (`V1:90,166,209,225`) — GDPR erasure and audit trail are in direct conflict, and the schema currently chooses erasure.

---

## 4. The three approver identities — role is unconstrained

| Actor column | SQL | `ON DELETE` |
|---|---|---|
| `commercial_credit.approved_by` | `V1:230` — `UUID REFERENCES app_user(id)` | **none ⇒ NO ACTION** |
| `escalation.resolved_by` | `V1:241` — `UUID REFERENCES app_user(id)` | **none ⇒ NO ACTION** |
| `agent_decision.approved_by` | `V1:416` — `UUID REFERENCES app_user(id)` | **none ⇒ NO ACTION** |

> ### CONFIRMED FINDING — the highest-value authorization rule in the system
>
> All three reference `app_user`, **not a supervisor-restricted subset**. There is **no CHECK, no trigger and no application code** constraining them to `role IN ('SUPERVISOR','ADMIN')`. Verified: a grep for any role constraint on these columns returns **0 matches**.
>
> ⇒ **A `CUSTOMER`-role account is a schema-valid approver of a credit granted to itself, and a schema-valid endorser of a Workforce Manager decision.** Every javadoc and test says "supervisor"; nothing enforces it.

`agent_decision.approved_by` is the **human-in-the-loop** column the ESPRIT jury cares about (`AgentDecision.java:37-40`). It currently accepts anyone.

---

## 5. The credit grantor — the one actor column that is not an account

`commercial_credit.granted_by UUID REFERENCES advisor(id)` — `V1:229`, `CommercialCredit.java:50-52`, javadoc: *"The advisor, **human or simulated**, whose ceiling this was charged against."*

Every other actor column resolves to an `app_user`. This one resolves to an `advisor`, which may be `is_simulated = true` with `user_id = NULL`. **A synthetic advisor can be the recorded grantor of a real commercial credit against a real customer** — there is no consistency CHECK between them (`V1:223-232`), and `commercial_credit` has no `run_id` to mark simulation traffic.

**The authenticated principal and the recorded grantor are different things.** When the AI agent calls `apply_credit` under a service key, the credential is the service and `granted_by` is the advisor on the conversation. Do not conflate them.

---

## 6. The `RULE` actor — the identity most likely to be forgotten

**Enum ⟷ CHECK — CONFIRMED:** `V1:240` `CHECK (raised_by IN ('ADVISOR','AI','RULE'))` ⟷ `EscalationRaisedBy.java:10-14`.

**Nothing acts under `RULE` today — CONFIRMED categorically.** The constant is referenced nowhere outside its own declaration. **No scheduler exists**: zero hits for `@Scheduled`/`@EnableScheduling` across all of `src/`; `infrastructure/scheduling/` contains only `package-info.java`.

> ⚠️ **Why it matters for authorization:** `RULE` is an **in-process** actor — an SLA sweep running inside the JVM under no HTTP request. It has **no `SecurityContext`**. Every ownership check written as *"the current principal owns this"* will NPE or silently deny the first time a scheduled job calls the same use case, **in a background thread, quietly**.
>
> **Decide before writing the first `@Scheduled`:** do jobs run under a synthetic system principal, or do use cases take an explicit actor parameter?

Correct asymmetry worth preserving: `escalation.resolved_by` is an `app_user` FK, so **a `RULE`-raised escalation can only be resolved by a human.**

---

## 7. The unauthenticated caller — the current default

**Profile `dev`** (`SecurityConfiguration.java:43-54`): `anyRequest().permitAll()` — **everything**, including all actuator endpoints (`application-dev.yml:25` sets `include: "*"`, which includes `/actuator/heapdump`), Swagger, and **every endpoint added from now on, automatically, with no further edit.**

**Every other profile** (`:65-76`): only `/actuator/health/**`; `anyRequest().denyAll()`.

> ⚠️ **`dev` is the shipped default.** `docker-compose.yml:40` — `${SPRING_PROFILES_ACTIVE:-dev}`; `.env.example:43` — `SPRING_PROFILES_ACTIVE=dev`. **A `docker compose up` with the template `.env` produces a fully open backend.** The well-reasoned `!dev` fallback is unreachable through any shipped path.

---

## 8. Other identities, briefly

- **§8 Four AI agents.** `AgentType` is a *self-declared attribute on a row*, not a principal. One service key will cover all four. If per-agent authorization is ever wanted, it must come from the credential, not the payload. `agent_type` has no CHECK constraint (`V1:406-407`, a recorded `-- REVIEW:`).
- **§10 `SYSTEM` sender.** ⚠️ If a message endpoint ever accepts `sender` from the request body, **a customer can forge a `SYSTEM` line** carrying platform authority. Derive it from the principal. Related and subtler: an AI reply is stored `sender='ADVISOR'` with `ai_generated=true` (`SchemaValidationTest.java:254-258`) — **`sender='ADVISOR'` does not mean a human wrote it.**
- **§12 Orphaned profile.** `ON DELETE SET NULL` manufactures real customers with `user_id IS NULL`. ⇒ **`user_id IS NULL` is not a valid test for "simulated".** The correct test is `is_simulated`, which is what the repositories use (`CustomerRepository:26`). Preserve that discipline.
- **§13 Human annotator.** `quality_evaluation` has **no FK to `app_user`** (`V1:443-452`). A human annotation is anonymous — inter-annotator reliability cannot be computed and a disputed score cannot be traced.
- **§19 Database identity.** The only working credential today. ⚠️ **UNVERIFIED** whether the runtime role and the migration role are separated Neon-side. They should be: the app never needs DDL (`ddl-auto: validate`). Also: `V2` is **not profile-gated** and Flyway is unconditionally `enabled: true` (`application.yml:54`), so **the four published dev accounts will be seeded into any database this migrates**, despite `V2:85-86` asking otherwise.

---

## 9. Sanity check — is reference data being treated as a user?

> **No. CONFIRMED clean. No scope creep found.**

`SkillCode` is correctly fenced: `SkillCode.java:6-7` — *"Unlike every other enum here, this one is not mapped to a column"*; `:11-14` — *"must never become a persisted column type"*. **Mechanically verified: exactly two hits repo-wide, both javadoc. Zero usages.** `Skill.code` is a plain `String` (`Skill.java:37-38`).

Every other reference entity checked and clean of actor fields: `Plan`, `QualityCriterion`, `ControlStrategy`, `SlaPolicy`, `RoutingRule`, `NetworkIncident`, `Scenario`, and `Ticket` (which has `customer_id` and `conversation_id` but **no assignee**).

`AdvisorSkill` is an association-with-payload between an actor and reference data, correctly modelled as an entity with `@EmbeddedId` — **not** an identity.

⚠️ One observation from the sweep, not a taxonomy error: `routing_rule`, `sla_policy`, `network_incident` and `scenario` are **admin-configurable and completely unattributed** — no `created_by` anywhere. Changing an SLA target or a routing rule leaves no trace of who did it. That is what makes an ADMIN's actions unreviewable.

---

## 10. Ranked — most likely to be missed by a "four roles" reading

1. **The approver identities are role-unconstrained (§4).** Privilege escalation, schema-valid today, and the rule that closes it — *"the approver must not be the beneficiary and must outrank the grantor"* — is a method-body rule no annotation can express.
2. **The service identity is a second, parallel authentication scheme (§7 in the table).** A design modelling only "JWT + role" has no slot for a caller that is not a user. `/internal` is also the only surface that can create money.
3. **The `RULE` actor bypasses the filter chain entirely (§6).** Breaks quietly, in a background thread.
4. **Simulated actors: the guarantee is conventional, not structural (§3).** One CHECK makes it unfalsifiable.
5. **The credit grantor is an `advisor`, possibly simulated (§5).** Breaks the "actor ⇒ user" assumption a four-roles taxonomy is built on.
6. **The unauthenticated caller is the current default (§7)**, and `dev` is the shipped profile.
7. **The orphaned profile makes `user_id IS NULL` a false proxy (§8).**

---

## 11. Cross-cutting lifecycle findings

1. **Hard-deleting an `app_user` is impossible once it has acted** — three FKs with no `ON DELETE` clause refuse it. Soft-delete via `active` is the only path, **and no code reads that flag.**
2. **`advisor` and `customer` have no `active` flag at all.**
3. **Deleting a customer destroys the audit trail** — four cascades.
4. **Nothing links `app_user.role` to the existence of its profile row.** An `ADVISOR`-role account with no `advisor` row has no `credit_limit` and no skills; both states are schema-valid and undetectable without a join.
5. **`customer.user_id` and `advisor.user_id` carry no `UNIQUE` constraint** (`V1:58`, `:130`, verified: 0 matches). ⇒ two profiles may share one account, and **one `app_user` may be simultaneously a customer and an advisor** — with access to their own customer file and their own `credit_limit`. **Add `UNIQUE` on both, or state why not.**
