# Next.js pivot — backend impact audit

**Date:** 2026-09-17 · **Backend commit audited:** `6b1b18d` · **Branch:** `main`

The frontend stack has moved from Angular to Next.js. An empty or `create-next-app`-default project
named `callverse_frontend` exists in a separate repository, not inspected from here.

This audit answers one question: **what does that swap change about the backend's assumptions?**
The short answer is *almost nothing*, and the reason to say so at length is that a stack pivot
mid-project invites re-litigating decisions that were never framework-dependent in the first place.

---

## 1. What stays identical — stated first, and plainly

Nothing in the list below is affected by which framework renders the browser. Each was verified
against source or the project context document at `6b1b18d`, not carried over from an earlier audit.

| Surface | Status under the pivot |
|---|---|
| The database schema, its 26 tables, and both Flyway migrations | **Unchanged.** It is SQL. A frontend framework cannot reach it. |
| The JPA entity model, the enums, the repository interfaces | **Unchanged.** Server-side, framework-agnostic. |
| The ownership-rule catalogue in `docs/user/OWNERSHIP_RULES.md` | **Unchanged.** These are use-case-body predicates. They are enforced in Java regardless of the caller. |
| The seven-phase roadmap | **Unchanged.** Phase 1 complete; Phases 2–7 as documented. |
| The four roles — `CUSTOMER`, `ADVISOR`, `SUPERVISOR`, `ADMIN` | **Unchanged.** Verified in `core/domain/enums/UserRole.java`: exactly these four, no more. |
| The error envelope's five fields | **Unchanged.** `timestamp`, `status`, `code`, `message`, `path`. Verified in `ErrorResponse.java`. |
| The rule *"Clients branch on `code`, never on `message`"* | **Unchanged.** Quoted verbatim from the project context. |
| The five frozen WebSocket topics | **Unchanged.** Quoted verbatim in §5 below. |
| The three inter-repository contracts | **Unchanged in shape.** Backend→AI, AI→Backend (the seven `/internal` tools), Backend→Frontend. |
| `/v3/api-docs` as the published contract | **Unchanged.** springdoc 2.6.0, serving today. |
| The `/api/v1` prefix convention, and `/internal/**` as a service-key surface | **Unchanged.** |
| *"N concurrent 401s must trigger exactly one refresh"* | **Unchanged.** Still a frozen frontend obligation. |
| The ten Phase 2 sub-phases (2.0–2.9) | **Unchanged in substance.** One value inside 2.3 changes — see §3. |

**Consequence:** no already-verified backend finding should be reopened because of this pivot. The
audit work in `docs/user/` retains its validity in full.

---

## 2. Three corrections to the premises this audit was commissioned under

Recorded because they affect what the scaffold prompt is allowed to assert.

1. **There are three inter-repo contracts, not five.** The project context's §6 is titled *"The
   contracts between the three repositories"* and contains exactly three headings: Contract 1
   (Backend → AI service), Contract 2 (AI service → Backend), Contract 3 (Backend → Frontend). The
   number five in this project belongs to two *other* things: the **five work lots** and the **five
   frozen WebSocket topics**.

2. **`/api/v1/auth/login` is not a frozen path.** The string does not appear anywhere in the project
   context. What is frozen is the `/api/v1` prefix and the fact that Phase 2 delivers "Login/refresh
   JWT". The scaffold prompt must therefore treat the auth path as *proposed*, not *frozen*, and
   isolate it behind a single constant so that one edit reconciles it when Phase 2 ships.

3. **Sub-phase 2.3 currently says "CORS config for the Angular origin".** That wording is now stale.
   The sub-phase itself already anticipated this: *"⚠️ UNVERIFIED — the exact allowed origin is
   information only the frontend repository has. Make it configurable; do not hardcode."* The pivot
   changes the *value* (a Next.js dev server is `http://localhost:3000` by default, against Angular's
   `4200`), not the design. No backend redesign follows.

---

## 3. What actually changes

Exactly three things, and only one of them is a decision:

1. **The CORS allowed-origin value** in sub-phase 2.3 — a configuration value, already specified as
   configurable. Not a design change.
2. **The generated-client output path** — see §7. Not a design change.
3. **Where the access token lives, and therefore whether CORS is needed at all** — this is the fork,
   and it is yours to decide. See §4.

Worth stating explicitly: **no document in this repository currently specifies where the access
token should live on the client.** Neither `JWT_AUTH_AUDIT.md` nor `ACTION_PLAN.md` commits to
memory, `localStorage`, or a cookie for the *access* token. The fork below is therefore genuinely
open, not a reversal of an existing decision.

---

## 4. The fork — BFF versus direct calls

**This is yours to decide. I recommend one below and do not adopt it.**

### Option D — direct: the browser calls Spring Boot

The browser holds the JWT and sends `Authorization: Bearer <token>` to Spring Boot. CORS is
required, which sub-phase 2.3 already provisions.

| For | Against |
|---|---|
| One credential, one trust boundary, one transport story. | The token is exposed to any XSS in the page. Holding it in memory (not `localStorage`) limits but does not eliminate this. |
| Requires **zero change** to the Phase 2 plan as written. | Server Components cannot fetch authenticated data — the token lives in the browser, not on the Next.js server. |
| Preserves the CSRF justification exactly as audited (§5). | In practice the app becomes largely client-rendered, which forfeits part of why one picks the App Router. |
| The STOMP connection uses the same credential as REST. | |

### Option B — BFF: Next.js Route Handlers proxy to Spring Boot

The browser talks only to its own origin. Next.js holds the access token server-side and fronts it
with a session cookie.

| For | Against |
|---|---|
| No CORS for REST — same origin throughout. | **Introduces a cookie, which reopens CSRF.** See §5. |
| The access token never reaches browser JavaScript; XSS cannot read it. | Auth logic now lives in two repositories instead of one. |
| Server Components *can* fetch authenticated data, which is the App Router's main draw. | An extra network hop on every request. |
| | **It does not extend to the WebSocket.** See below. |

### The WebSocket wrinkle that decides it

STOMP over WebSocket is a persistent, bidirectional connection. A thin BFF proxy does not extend to
it the way it extends to REST: Next.js Route Handlers do not support the WebSocket upgrade in the
App Router's standard serverless-compatible runtime, and proxying one would mean Next.js holding an
open socket per connected client — an architecture the framework is not shaped for and which the
project has no reason to take on.

**So the browser must speak STOMP to Spring Boot directly, whichever option wins for REST.**

Under Option B that produces a system with two different trust boundaries and two different
credential shapes: REST authenticated by a session cookie that Spring Boot never sees, and a
WebSocket authenticated by something the browser must be handed separately — a short-lived
"socket ticket" endpoint, a second token type, and a second revocation story. That is a material
amount of complexity to carry for a student project with a fixed deadline, and it exists solely
because REST was optimised in isolation.

### Recommendation

**Option D (direct).** Not because it is more secure in the abstract — Option B's XSS posture is
genuinely better — but because it keeps the *whole system* coherent. One credential is presented
identically on both transports: `Authorization: Bearer <jwt>` on REST, and the same JWT on the STOMP
`CONNECT` frame. One trust boundary. One revocation story. It also requires no change whatsoever to
the Phase 2 plan, which already provisions CORS in 2.3 and STOMP authentication in 2.8.

The honest cost of Option D is the one in the table: authenticated data fetching moves to Client
Components, and the App Router's server-side data story goes largely unused. That is a real loss,
and it is the strongest argument for Option B. I still recommend D, because two trust boundaries is
a worse thing to explain to a jury — and to debug at 2 a.m. in defence week — than a client-rendered
dashboard.

**Decision required from you.** The scaffold prompt in `NEXTJS_SCAFFOLD_PROMPT.md` is written
against Option D and carries a clearly marked block stating precisely what changes if you choose B,
so it stays correct either way.

---

## 5. CSRF re-examination

The current posture is justified, and the justification is narrow and conditional. Verbatim from
`docs/user/JWT_AUTH_AUDIT.md`:

> **CSRF disabled is justified — CONFIRMED.** A grep for `cookie|HttpSession|JSESSIONID` across
> `src/` returns exactly one hit: the comment itself at `:47`. No cookie is read or written
> anywhere. Re-examine **only** if a refresh token lands in an `HttpOnly` cookie.

And from `docs/user/ACTION_PLAN.md`, sub-phase 2.4:

> ⚠️ Option 1 + `HttpOnly` cookie ⇒ **the CSRF analysis changes** and `csrf().disable()` must be
> revisited in both chains. It is correctly justified today only because no cookie exists anywhere.

**Applied to this pivot:**

- **Under the recommended Option D, the justification survives intact.** No cookie is introduced.
  `csrf().disable()` in both chains remains correct, for the reason already documented.

- **Under Option B, the justification is void the moment the session cookie exists** — and it is
  void *silently*, because nothing in the build fails. This is precisely the failure mode the
  original audit warned about. Choosing B obliges you to: set `SameSite=Strict` or `Lax` on the
  session cookie; keep `HttpOnly` and `Secure` on; and add CSRF protection for state-changing
  requests. Next.js Server Actions carry an origin check of their own, but **Route Handlers do
  not** — a `POST` Route Handler reached with an ambient cookie is exactly the classic CSRF target.

- **Independently of this fork, Decision 2 can reopen CSRF on its own.** If you choose the persisted
  revocable refresh-token table *and* deliver that refresh token in an `HttpOnly` cookie, a cookie
  enters the system under Option D too, and the same obligations apply. The fork and Decision 2 are
  separate doors to the same room.

**Net:** the recommended path changes nothing about CSRF. Two decisions that remain yours — the fork,
and Decision 2's cookie question — can each change it, and neither will announce itself.

---

## 6. WebSocket transport — the honesty note

**The backend has no WebSocket implementation.** Verified at `6b1b18d`:
`src/main/java/com/callverse/host/api/websocket/` contains exactly one file, `package-info.java`.
There is no broker configuration, no `@MessageMapping`, no `StompEndpointRegistry` call anywhere in
`src/main/java`. The `spring-boot-starter-websocket` dependency is present in `pom.xml`, which means
the capability is *available*, not that it is *built*.

Per the project's phase roadmap, STOMP ships in **Phase 4** ("Business domain and real time"), and
its authentication ships in sub-phase **2.8**. Neither has started.

The five topic names are nonetheless frozen and safe to build against, verbatim:

```
/topic/queue/{skill}        → arrivals and departures from a queue
/topic/conversation/{id}    → messages
/topic/supervision/kpi      → live KPIs
/topic/supervision/alerts   → SLA alerts
/topic/runs/{runId}         → simulation run progress
```

> Each subscription is filtered by role — an advisor only receives their own queue.

**No message payload shape is documented for any of these topics.** The frontend must therefore
define its own provisional payload types and mark them as unconfirmed.

**Instruction carried into the scaffold prompt:** build the real-time layer against these five topic
names behind a pluggable transport interface, so the concrete STOMP client is one swappable module.
The exact handshake — how the JWT is presented on `CONNECT`, whether a native `WebSocket` or a SockJS
fallback is used, the heartbeat configuration — **cannot be known until Phase 4 and sub-phase 2.8
ship**, and must not be guessed at in a way that hides the uncertainty.

---

## 7. OpenAPI code generation

`openapi-typescript` works identically against a Next.js project; it is a build-time CLI that reads a
JSON document and writes a `.d.ts` file, and it has no knowledge of and no opinion about the consuming
framework. **The only thing that changes is the output path.**

- **Source, unchanged:** `http://localhost:8080/v3/api-docs`, served by springdoc 2.6.0.
- **Output, changed:** now inside `callverse_frontend`, e.g.
  `src/shared/api/generated/callverse-api.d.ts`, rather than an Angular `src/app/core/api/` path.
- The `make swagger` target in this repository still prints the generation command; its suggested
  output path now refers to the retired Angular layout and should be updated when the frontend path
  is settled.

**One caveat the frontend must be told.** As of `6b1b18d` the document describes exactly one
endpoint — `GET /api/v1/health/status` — plus the `ErrorResponse` and `HealthStatusResponse` schemas
and two declared-but-unenforced security schemes (`bearerAuth`, `serviceKey`). Generating types today
therefore yields a nearly empty client. That is expected and is precisely why the scaffold builds
against a mock layer; regenerating is a one-command step as each backend phase lands.

---

## 8. Summary

- Nothing about the domain, the schema, the roles, the envelope, the topics or the roadmap changes.
- One configuration value changes (the CORS origin) and one output path changes (the generated client).
- One decision is open and is yours: **BFF versus direct**, recommended as **direct (Option D)**.
- CSRF is unaffected on the recommended path, and can be silently invalidated on two others.
- The WebSocket is a contract, not an implementation, and the scaffold must say so.
