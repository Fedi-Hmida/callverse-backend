# CallVerse — Phase 2 Action Plan (User Management, Authentication, Authorization)

> **For agentic workers:** execute task-by-task. Steps use `- [ ]` for tracking. No task begins before its predecessor's exit criterion is met.

**Goal:** take authentication and authorization from **zero** to a working, tested, contract-conformant system — and close the authorization holes catalogued in `OWNERSHIP_RULES.md` before they ship.

**Architecture:** authentication mechanics live in `infrastructure.security`. Coarse role gates live in annotations. **Fine-grained ownership rules live inside use-case method bodies**, per this project's documented convention. Nothing authentication-related enters `core.domain`.

**Spec:** `USER_TAXONOMY.md`, `JWT_AUTH_AUDIT.md`, `USER_MANAGEMENT_AUDIT.md`, `OWNERSHIP_RULES.md` (this directory) · `CALLVERSE_PROJECT_CONTEXT.md` §6, §9, §11.

## Global constraints

- `docs/CALLVERSE_DB_SCHEMA.md` is the schema's source of truth, **provisioned by hand by a teammate**. Report divergences; never fix locally.
- `ddl-auto` stays `validate`. No secret in the repo. English throughout. Conventional commits, no AI attribution.
- `./mvnw clean install` before pushing; requires Docker.
- Every new ownership rule gets a test that **fails before it is written**.

---

## 0. Where we actually start

Three facts, all verified in this pass, that determine the ordering:

1. **Phase 2 and Phase 3 are both entirely ahead.** No filter, no login endpoint, no `UserDetailsService`, no `PasswordEncoder`, **zero `@PostMapping` in the whole repository**, `SecurityContextHolder` referenced nowhere, `AppUserRepository` with **zero callers**. The data layer is complete; the mechanism layer is empty.
2. **Two claims in the repo's own docs are false, proven by running the app.** `JWT_SECRET` does **not** gate startup (the app booted fine without it), and a generated security password **is** logged on every boot. See `JWT_AUTH_AUDIT.md` §0.
3. **`dev` is the shipped default profile** and its chain is `anyRequest().permitAll()`. This is the only finding **exploitable today**, and it is an active misconfiguration in a tracked file rather than a missing feature.

---

## 1. The two escalated decisions — **yours to make, not resolved here**

### Decision 1 — Customer self-registration, or provisioning for all four roles?

| | **A: all four roles provisioned by ADMIN** | **B: customers self-register, staff provisioned** |
|---|---|---|
| Scope | One `POST /api/v1/admin/users`, ADMIN-gated | Two flows: a public `POST /api/v1/auth/register` plus the admin one |
| Attack surface | None public | **A public, unauthenticated write endpoint** — needs rate limiting, email-uniqueness races, and a decision on email verification |
| Demo realism | An admin creating a customer is odd for a telecom portal | Matches how a real customer portal works |
| 3iL grading | CRUD depth is graded; an admin back-office shows it | Self-registration is the more conventional demo |
| Effort | ~1 sub-phase | ~2 sub-phases + abuse handling |
| Schema | none | none |

**Recommendation: A, provisioning only.** Three reasons specific to this project. The four seeded accounts already cover every role, so the demo does not need registration. A public write endpoint is the only unauthenticated write in the system and it lands in a codebase where `dev` currently permits everything. And `CALLVERSE_PROJECT_CONTEXT.md` never lists self-registration among the six modules — the customer portal is described as *"Account, contract, invoices, tickets, chat"*, all post-login.

**But it is your call**, and a jury may ask why a customer portal has no sign-up. The defensible answer if you take A: *"telecom accounts are provisioned at contract signature, not self-served — registration happens in the CRM, not the portal."* That is true of real operators.

### Decision 2 — Stateless refresh, or a persisted revocable `refresh_token` table?

Full trade-off table in `JWT_AUTH_AUDIT.md` §7. Summary:

| | **Option 1 — persisted table** | **Option 2 — stateless rotation** |
|---|---|---|
| Revocation | **Yes** — logout works, "sign out all devices" possible, refresh-reuse detection possible | **No** — a copied token works until `exp` |
| Cost | **A 27th table in a hand-provisioned schema — a cross-person round trip that must be scheduled** | **Zero schema change**, entirely within your control |
| Defence | Answers *"how do you log a user out?"* | The honest answer is *"we don't, we wait"* |

**Recommendation: Option 2 (stateless) for the deadline, with one condition** — cut the access-token lifetime from the current 1 hour to **15 minutes**, and say so explicitly in the report as a deliberate trade. Reasoning: `CALLVERSE_PROJECT_CONTEXT.md:516` already lists refresh tokens as the fifth thing to cut; Option 1's real cost is not code but **a dependency on another person's availability**, which is the wrong thing to put on a critical path in week 2.

> ⚠️ **If you choose Option 1, decide NOW, not later** — and batch the schema edit with S-1 (the `UNIQUE` constraints) and S-7 in **one** hand-provisioning request. Splitting schema changes across multiple manual round trips is the expensive failure mode here, and it is avoidable purely by ordering.

**Neither decision blocks sub-phases 2.0 through 2.3.** Start those while deciding.

---

## 2. Sub-phases

### 2.0 — Fail closed, and make role annotations actually work ⭐ START HERE

**Objective:** remove the two conditions under which everything built later is silently unprotected. **~30 minutes. No new feature.**

**Files:** `docker-compose.yml:40` · `.env.example:43` · `infrastructure/security/SecurityConfiguration.java:32-34` · `README.md` · `application.yml:81-82`

- [ ] Invert the compose default so a missing profile **fails closed**: `${SPRING_PROFILES_ACTIVE:-prod}`, and require `dev` to be set explicitly.
- [ ] Add **`@EnableMethodSecurity`** to `SecurityConfiguration`. Zero behaviour change today (there are zero `@PreAuthorize` in the codebase) — but without it **every role annotation added later is inert metadata that compiles, passes review, and enforces nothing.**
- [ ] Correct the two false comments: `SecurityConfiguration.java:60-63` (the generated password **is** logged today) and `application.yml:81-82` / `README.md:66` (the app **does not** refuse to start without `JWT_SECRET` — it will once 2.1 reads the property).

**Unblocks:** nothing directly. **Prevents:** every later sub-phase from being silently ineffective.
**Exit criterion:** `docker compose config` shows a non-dev default; `grep -c EnableMethodSecurity` returns 1; `./mvnw clean install` green.
**Most likely failure mode:** skipping this because it delivers no visible feature — and then discovering at Phase 4 that 40 endpoints carry `@PreAuthorize` annotations that never executed.
**Juries:** 3iL (security posture is graded directly).

---

### 2.1 — Password encoding and login

**Objective:** issue a JWT for valid credentials. The first `@PostMapping` in the repository.

**Files:**
- `infrastructure/security/PasswordEncoderConfiguration.java` *(new)*
- `infrastructure/security/JwtTokenService.java` *(new)* — the first code to import `io.jsonwebtoken`
- `core/application/interfaces/TokenIssuer.java` *(new port — no Spring, no jjwt types)*
- `core/application/features/auth/commands/LoginCommand.java`, `LoginCommandHandler.java` *(new — plain Java)*
- `infrastructure/config/AuthFeatureConfiguration.java` *(new — wires the handler, copying `HealthFeatureConfiguration`)*
- `host/api/controllers/AuthController.java` *(new)* · `host/api/dto/request/LoginRequest.java` · `host/api/dto/response/TokenResponse.java`
- Modify `AppUserRepository` — add `findByEmailIgnoreCaseAndActiveTrue`

- [ ] Declare a **bare `BCryptPasswordEncoder`** bean. **Do not use `PasswordEncoderFactories.createDelegatingPasswordEncoder()`** — it expects a `{bcrypt}` prefix and **will reject all four seeded hashes** with `There is no PasswordEncoder mapped for the id "null"`.
- [ ] **First, verify the seeded hashes match `CallVerse!Dev2026`.** Currently ⚠️ UNVERIFIED. A mismatch presents as "login is broken" on the day login ships, with an invisible cause.
- [ ] Use `findByEmailIgnoreCaseAndActiveTrue` — **not** `findByEmailIgnoreCase`. `V1:45` states *"inactive users are never routed to or authenticated"*; the existing method does not filter `active`, and nothing else will.
- [ ] Return an identical error for unknown-email and wrong-password. A distinguishable response is a user-enumeration oracle.
- [ ] Reading `jwt.secret` here is what finally activates the documented fail-fast on a missing `JWT_SECRET`.

**Unblocks:** **the frontend leaves the mock behind** — this is the single most frontend-unblocking item in the plan.
**Exit criterion:** a Testcontainers test logs in as each of the four seeded accounts and receives a parseable JWT; a wrong password and an unknown email both return the same 401 shape.
**Most likely failure mode:** the delegating-encoder trap above, or forgetting `active`.
**Juries:** 3iL (authentication is graded directly).

---

### 2.2 — The JWT filter and `SecurityContext`

**Objective:** a valid token populates the `SecurityContext` so downstream code can identify the caller. **`SecurityContextHolder` is currently referenced nowhere in the repository.**

**Files:** `infrastructure/security/JwtAuthenticationFilter.java` *(new)* · `infrastructure/security/CallVerseUserDetailsService.java` *(new)* · `infrastructure/security/AuthenticatedPrincipal.java` *(new)* · modify `SecurityConfiguration`

- [ ] `OncePerRequestFilter`, registered `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`.
- [ ] The principal must carry **`app_user.id`, the role, and the resolved `customer.id` / `advisor.id`** — every ownership rule in `OWNERSHIP_RULES.md` needs those, and resolving them per request otherwise means a database round trip per check.
- [ ] Add `CustomerRepository.findByUserId` and `AdvisorRepository.findByUserId` — **neither exists**, and **§0 of `OWNERSHIP_RULES.md` shows every ownership rule is blocked until they do.**
- [ ] ⚠️ Until S-1 (`UNIQUE` on `user_id`) is applied, these must return `List` and the handler must reject an ambiguous result rather than pick one.
- [ ] Declaring a `UserDetailsService` here **also silences the generated-password warning** — it is the bean Boot backs off on.

**Unblocks:** every subsequent sub-phase.
**Exit criterion:** a test asserts that a request with a valid token reaches a controller with a populated principal carrying the correct `customer.id`; an expired token and a tampered signature are both rejected.
**Most likely failure mode:** resolving the profile id per-request and creating an N+1 on every authorized call.
**Juries:** 3iL.

---

### 2.3 — Auth error shape, and CORS

**Objective:** make 401/403 conform to the frozen error envelope, and let the browser call the API at all.

**Files:** `infrastructure/security/RestAuthenticationEntryPoint.java` *(new)* · `infrastructure/security/RestAccessDeniedHandler.java` *(new)* · `infrastructure/security/CorsConfiguration.java` *(new)* · modify `GlobalExceptionHandler`

- [ ] **Both halves are required.** Filter-chain denials never reach `@RestControllerAdvice` — the security chain runs **before `DispatcherServlet`**. So: an entry point + denied handler wired into `exceptionHandling(...)` in **both** chains, **and** an `@ExceptionHandler({AuthenticationException, AccessDeniedException})` for method-security denials thrown inside MVC. **Doing only one leaves half the denials off-contract.**
- [ ] ⚠️ **This sub-phase must not be skipped once 2.0 lands.** With `@EnableMethodSecurity` enabled, `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)` **matches `AccessDeniedException` first**, turning every routine permission denial into **HTTP 500 with an ERROR-level stack trace** — the exact failure its own javadoc says it is designed to avoid.
- [ ] Return **401** for missing/invalid credentials and **403** for authenticated-but-forbidden. Today there is no entry point, so Spring falls back to `Http403ForbiddenEntryPoint` and **returns 403 for everything, never 401** — contradicting both `SecurityConfiguration.java:62` and the frontend's documented "N concurrent 401s trigger exactly one refresh" rule, which cannot fire on a 403.
- [ ] CORS: **absent entirely today** (0 hits repo-wide). ⚠️ **UNVERIFIED** — the exact allowed origin is information only the frontend repository has. Make it configurable; do not hardcode.

**Unblocks:** the frontend's error handling and its refresh interceptor.
**Exit criterion:** `MockMvc` tests assert the five-field envelope on both a 401 and a 403; a preflight `OPTIONS` succeeds from the configured origin.
**Juries:** 3iL, and it is an inter-repo contract.

---

### 2.4 — Refresh (resolves Decision 2)

**Files:** `core/application/features/auth/commands/RefreshTokenCommand*.java` · `host/api/controllers/AuthController.java` (extend) · *(Option 1 only: `RefreshToken` entity + repository + `V3` migration)*

- [ ] Implement the chosen option. **Rotate on every refresh** either way.
- [ ] Option 1 only: store a **hash** of the token, never the token. Add a cleanup for expired rows.
- [ ] ⚠️ Option 1 + `HttpOnly` cookie ⇒ **the CSRF analysis changes** and `csrf().disable()` must be revisited in both chains. It is correctly justified today only because no cookie exists anywhere.
- [ ] Document the exposure window in the report either way. Currently **1 hour** with **no revocation mechanism of any kind**.

**Exit criterion:** refresh returns a new pair and the old refresh token is unusable.
**Juries:** 3iL (a stated grading point).

---

### 2.5 — Ownership rules ⭐ THE LARGEST AND MOST IMPORTANT SUB-PHASE

**Objective:** implement the catalogue in `OWNERSHIP_RULES.md`. **This is not one work item; it is ~36 rules across 22 ownership-blind repository methods.**

**Files:** every handler under `core/application/features/*/queries` and `*/commands`; new scoped methods across `infrastructure/persistence/repositories/`.

- [ ] **Never trust a path variable for an owned resource.** Resolve the id from the principal and ignore the path.
- [ ] Implement the five highest-risk rules first, in this order — they are ranked by (probability of omission × damage) in `OWNERSHIP_RULES.md` §I:
  1. **A4 invoices / A8 transcripts** — three hops, no query expresses them. `invoice` has **no `customer_id` column**; the join through `contract` is mandatory. **Damage: every customer's billing history readable by changing one UUID.**
  2. **B2 field-level write on `advisor.credit_limit`** — a row-ownership check passes correctly while an advisor raises their own ceiling. **Refutes the project's central claim in one request.**
  3. **A13/A14 anti-transitivity** — a customer owns the conversation but must **not** read its `quality_evaluation` (the advisor's performance review) or `escalation`. A well-factored `assertOwnsConversation` helper is *exactly* what opens this hole.
  4. **B12 conversation-scoped customer panel** — without it, `hasRole('ADVISOR')` on `GET /customers/{id}` is a **full customer-database read for every advisor**.
  5. **C1/C4 self-attribution and separation of duties** — `approved_by` set only from the caller's own principal, never the body; and the approver must not be the granter. **Nothing stops self-approval today.**
- [ ] Add scoped repository methods rather than filtering in memory.
- [ ] ⚠️ **The inherited surface is larger than the named list.** 22 of 23 repositories extend `JpaRepository` and inherit `findById`/`findAll`/`deleteById` — all ownership-blind, none reviewed. **Consider narrowing the riskiest ones to the bare `Repository` marker**, exactly as `MetricSampleRepository` already does for writes.

**Exit criterion:** for each of the five rules, a Testcontainers test proves **principal A cannot read principal B's resource** — written **red first**.
**Most likely failure mode:** treating this as one checklist item and doing the coarse gates only.
**Juries:** 3iL primarily; A13 is also a privacy matter.

---

### 2.6 — Human-in-the-loop authorization ⭐ THE ESPRIT SUB-PHASE

**Objective:** make SUPERVISOR approval of a Workforce Manager decision a real, enforced, auditable authorization decision.

**Files:** `core/application/features/workforce/commands/ApproveAgentDecision*.java` · `.../RejectAgentDecision*.java` · `host/api/controllers/SupervisionController.java`

- [ ] `agent_decision.approved_by` and `escalation.resolved_by` and `commercial_credit.approved_by` are **all plain `REFERENCES app_user(id)` with no role constraint** — a `CUSTOMER` is a schema-valid approver **today**. The role gate exists only in the method body you are about to write.
- [ ] **Self-attribution** (never from the body) and **idempotence** (refuse if already non-null). There is no `updated_at`, no version column and no history on `agent_decision`, so **an overwrite is undetectable after the fact**.
- [ ] **Per-decision, never per-run.** A bulk-approve endpoint makes the human-in-the-loop claim indefensible to a jury.
- [ ] ⚠️ **HITL-1 — "reject" is not representable in the current schema.** Only `approved_by` exists, so `NULL` is overloaded across *not yet reviewed* and *reviewed and rejected*. Consequences: no pending-review queue, a rejection leaves no trace, and **the report cannot state an approval rate because the denominator is not recorded.** Needs `review_status` + `reviewed_at` + `rejection_reason`. **Escalate to the schema owner — this blocks an ESPRIT deliverable.**
- [ ] ⚠️ **HITL-3 — decide explicitly whether approval *gates* the action or merely records it.** As built, the table logs decisions **already taken** — that is *human-on-the-loop*, a different claim from *human-in-the-loop*. **Settle before the report is written.**

**Exit criterion:** a non-SUPERVISOR is refused; a supervisor cannot approve twice; a supervisor cannot approve a credit they granted.
**Juries:** **ESPRIT primarily** — this is the XAI/governance deliverable. Also 3iL.

---

### 2.7 — The `/internal` service key (a distinct scheme, not a JWT variant)

**Objective:** authenticate the Python AI service. **Not user auth — do not conflate.**

**Files:** `infrastructure/security/ServiceKeyAuthenticationFilter.java` *(new)* · `infrastructure/security/InternalApiSecurityConfiguration.java` *(new chain)* · `.env.example` · `application.yml` · `docker-compose.yml`

- [ ] **A single shared secret in configuration, compared in constant time.** One caller, one key ⇒ **no database table**. A `service_key` table would buy per-client revocation with exactly one client — over-engineering at this scope.
- [ ] **The real gap is config, not schema:** `.env.example` and `application.yml` have **no slot for this key at all**, and `docker-compose.yml:72` passes the Python service **no credential of any kind**.
- [ ] A **separate `SecurityFilterChain` for `/internal/**`** with `@Order` — ⚠️ **neither existing chain declares `@Order`**; adding a third makes ordering non-deterministic without it.
- [ ] ⚠️ **E6 blocker:** the credit ceiling reads `advisor.credit_limit`, but `conversation.advisor_id` is **nullable** and an AI-handled first-line conversation has **no advisor** ⇒ **no ceiling and nothing to compare against**. Resolve S-8 (does the Customer Advisor get a real `advisor` row?) **before this ships** — no method-body check closes it otherwise.
- [ ] ⚠️ `since` in `sumGrantedToCustomerSince` has **no defined source** — no column, config key or document. Define the rolling window.

**Unblocks:** **the AI lot, blocked today.** `CALLVERSE_PROJECT_CONTEXT.md` calls `/internal` *"the most important integration milestone"* and names these endpoints **"Never cut."**
**Exit criterion:** `/internal/**` returns 401 without the key and 200 with it, in **every** profile including `dev`.
**Most likely failure mode:** shipping `/internal` into the `dev` chain's `permitAll` and never noticing, because it works.
**Juries:** both — 3iL (service auth) and ESPRIT (the backend-is-authority claim rests on it).

---

### 2.8 — WebSocket / STOMP authentication

**Objective:** authenticate the handshake **and authorize each subscription**.

**Files:** `host/api/websocket/WebSocketSecurityConfiguration.java` *(new)* · `.../StompAuthChannelInterceptor.java` *(new)*

- [ ] ⚠️ **`authorizeHttpRequests` secures only the HTTP handshake, not message destinations.** Authorizing `SUBSCRIBE` requires `@EnableWebSocketSecurity` + an `AuthorizationManager<Message<?>>` — **a third mechanism, separate from both the filter chain and `@EnableMethodSecurity`.**
- [ ] Enforce the A15/B14 predicates **at subscribe time**: a customer may subscribe only to their own conversation; an advisor only to queues for skills they hold (`CALLVERSE_PROJECT_CONTEXT.md:328`: *"an advisor only receives their own queue"*).
- [ ] **A handshake-only check leaves a second wide-open door:** `SUBSCRIBE /topic/conversation/<any uuid>` — a live streaming feed of any conversation, bypassing every REST check, leaving no request log.

**Exit criterion:** a test proves a customer cannot subscribe to another customer's conversation topic.
**Juries:** 3iL (real time is a graded criterion — the feature that demos best and leaks worst).

---

### 2.9 — User provisioning endpoints *(only if time allows — resolves Decision 1)*

**Files:** `core/application/features/auth/commands/CreateUser*.java`, `DeactivateUser*.java` · `host/api/controllers/AdminUserController.java`

- [ ] `POST /api/v1/admin/users` (ADMIN), `PATCH .../deactivate`.
- [ ] Deactivation sets `active = false`. **Note it does nothing to an already-issued token** unless Decision 2 chose Option 1 — state that limitation explicitly.
- [ ] **`active` cannot currently be flipped by any code**; `AppUser` is never instantiated or mutated in production. This is the sub-phase that changes that.

**Juries:** 3iL (CRUD depth).

---

## 3. Jury scoring

| Sub-phase | 3iL (web) | ESPRIT (AI/DS) | Cut if time runs out? |
|---|---|---|---|
| 2.0 fail closed + method security | **HIGH** | low | **Never** — 30 minutes, and everything later depends on it |
| 2.1 login | **HIGH** | low | Never |
| 2.2 JWT filter | **HIGH** | low | Never |
| 2.3 error shape + CORS | **HIGH** | low | Never — inter-repo contract |
| 2.4 refresh | MED | low | **Cuttable** — a longer-lived access token survives a demo. Already listed as the fifth thing to cut |
| 2.5 ownership rules | **HIGH** | MED | **Never** — this *is* the authorization grade |
| 2.6 human-in-the-loop | MED | **HIGH** | **Never** — it is an ESPRIT deliverable |
| 2.7 `/internal` service key | MED | **HIGH** | Never — "never cut" per the context document |
| 2.8 WebSocket auth | **HIGH** | low | Partially — handshake-only is defensible if stated |
| 2.9 provisioning | MED | none | **Cuttable** — four seeded accounts cover the demo |

---

## 4. What stays a documented skeleton if time runs out, and how to defend it

Consistent with this project's existing pattern of **naming accepted debt rather than hiding it**.

| Deferred | Defence |
|---|---|
| **Refresh tokens / revocation** | *"Access tokens are short-lived (15 min) and stateless by design. Revocation requires server-side token state, which is a table in a hand-provisioned schema — a cross-team dependency we judged not worth taking on a two-week path. The exposure window is bounded and stated."* **Only defensible if you actually shorten the expiry.** |
| **Supervisor team scoping** | *"One centre, one supervisory scope. `/topic/supervision/kpi` is centre-wide by design — partitioning would fragment the KPI dashboard that is the headline deliverable."* A design statement, not a gap. |
| **Lockout / brute-force** | *"Four known accounts on a demo system with a published password. BCrypt at cost 10 rate-limits by construction, and lockout introduces its own DoS vector — an attacker locks out any user by guessing wrong."* |
| **Password reset** | *"Requires an email delivery path outside this project's scope. With provisioned accounts, reset has no user."* |
| **Audit log** | *"Designed, not implemented."* Already named as cuttable. ⚠️ **But note cutting it removes the only detection for a `approved_by` overwrite** — say that too, it shows you know what you traded. |
| **WebSocket per-subscription authorization** | Defensible **only** if the handshake is authenticated and you say plainly that destination-level authorization is not implemented. Do **not** claim subscriptions are filtered by role if they are not. |

---

## 5. Definition of done for Phase 2

- [ ] Compose defaults to a non-dev profile; `@EnableMethodSecurity` present
- [ ] Four seeded accounts verified to authenticate with the documented password
- [ ] Login issues a JWT; the filter populates a principal carrying `app_user.id`, role, and resolved profile id
- [ ] 401 and 403 both return the five-field envelope; CORS configured
- [ ] The five highest-risk ownership rules implemented, **each with a red-first test proving cross-principal access is refused**
- [ ] `agent_decision` approval is SUPERVISOR-gated, self-attributed, idempotent, per-decision
- [ ] `/internal/**` rejects a request without the service key **in every profile**
- [ ] `./mvnw clean install` green; working tree clean
- [ ] **Decision 1 and Decision 2 recorded in writing, with their reasoning**
- [ ] Schema recommendations forwarded and batched: S-1 (`UNIQUE` on both `user_id`), S-2 (`review_status`), the `is_simulated ⇒ user_id IS NULL` CHECK, and Option 1's table **if chosen**

---

## 6. What this unblocks, per lot

| Lot | After 2.1–2.3 | After 2.5–2.6 | After 2.7 |
|---|---|---|---|
| **Frontend** | **UNBLOCKED** — leaves the mock, real login, real error envelope, CORS | Per-role views become meaningful | — |
| **AI lot** | — | — | **UNBLOCKED** — the Customer Advisor runs against real data |
| **RL lot** | — | 2.6 gives human-in-the-loop a real enforcement point | — |
| **Quality lot** | — | Ownership rules make `quality_evaluation` access correct | — |

---

## Self-review

**Spec coverage:** taxonomy §4 approvers → 2.6 · §6 `RULE` actor → ⚠️ open, see below · JWT audit §5 method security → 2.0 · §6 error shape → 2.3 · §7 refresh → 2.4 + Decision 2 · §8 service key → 2.7 · user-management Part A → 2.9 · Part B encoder → 2.1 · ownership catalogue → 2.5, 2.6.

**Gap I am naming rather than scheduling:** the **`RULE` / scheduled-job identity** has no sub-phase. It cannot: no scheduler exists yet, so there is nothing to secure. **But the convention must be decided before the first `@Scheduled` is written** — a background job has no `SecurityContext`, and every ownership rule written as *"the current principal owns this"* will fail quietly in a background thread. Decide: synthetic system principal, or an explicit actor parameter on use cases.

**Placeholder scan:** no TBD. Two items are deliberately parameterised on your decisions (2.4, 2.9) with both branches specified.

**Type consistency:** `findByUserId`, `findByEmailIgnoreCaseAndActiveTrue`, `AuthenticatedPrincipal`, `TokenIssuer`, `sumGrantedToCustomerSince` — used identically across 2.1, 2.2, 2.5, 2.7.
