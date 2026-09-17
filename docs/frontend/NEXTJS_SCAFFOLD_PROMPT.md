# The Next.js scaffold prompt

> **How to use this file.** Everything below the horizontal rule is the prompt. Paste it into a
> Claude Code session running in VS Code with `callverse_frontend` open as the working directory.
> It is written to be self-contained: the session receiving it has no memory of the backend
> repository and no access to it, so every contract value it needs is written out in full rather
> than referenced.
>
> **Before pasting, resolve one thing.** §4 of the prompt is written against **Option D (direct
> browser-to-backend calls)**, the recommendation in `NEXTJS_PIVOT_AUDIT.md`. If you decide on
> Option B (BFF) instead, replace the marked block in §4 with the alternative that follows it. The
> rest of the prompt is unaffected either way.

---

# TASK — Scaffold the CallVerse frontend: Next.js App Router, contract-first, mock-driven

You are scaffolding the frontend for **CallVerse**, a digital twin of a telecom customer relation
center. This is academic coursework defended before **two juries**: 3iL Ingénieurs (web engineering)
and ESPRIT (Data Science / AI). Both juries read the same application and grade different things in
it. That fact drives several decisions below that would otherwise look arbitrary.

You are working in `callverse_frontend`. The Spring Boot backend lives in a **separate repository
you cannot see from here**. Every backend fact you need is written out in this prompt. Do not
speculate about backend behaviour beyond what is stated here, and do not invent endpoints.

---

## NINE PERSPECTIVES, HELD SIMULTANEOUSLY

Every decision and every line you write must be defensible from all nine at once.

1. **Senior Next.js / React engineer** — App Router, Server versus Client Components, Route
   Handlers, Server Actions, middleware-based route protection. You know which of these is a
   default and which is a decision.
2. **Frontend architecture practitioner** — a feature-sliced structure mirroring the backend's own
   CQRS-by-feature discipline, not a generic starter layout. The backend organises by feature and
   never by technical role; so does this.
3. **API / contract integration architect** — the OpenAPI document is a published contract, not
   documentation. Types are generated, never hand-written.
4. **Security engineer** — token handling, route guards, and the fact that a guard in middleware is
   a convenience for the user, never a security boundary. The backend is the only authority.
5. **Real-time engineer** — STOMP over WebSocket, and its constraint of running only in a Client
   Component.
6. **Test engineer** — Vitest and Playwright held to the same red-before-green standard the backend
   already meets. A test that has never failed has proven nothing.
7. **3iL web jury evaluator** — four genuinely distinct role UIs, real authentication and
   authorisation, substantial CRUD, non-trivial business logic, real time, clean architecture,
   worked UX, security.
8. **ESPRIT AI/DS jury evaluator** — the simulation studio, run-versus-run comparison, and the
   human-in-the-loop approval screen. The two most commonly failed items across both juries are a
   missing or too-weak baseline, and results presented without variance.
9. **Tech lead** — this is a scaffold. Its value is in what it makes cheap later, not in what it
   demonstrates now.

---

## GROUND RULES (non-negotiable)

1. **Verify the current state of this project before you write anything.** `callverse_frontend` may
   be genuinely empty, or may hold `create-next-app` defaults, or something in between. Run `ls -la`,
   read `package.json` if one exists, check `git log`. **Report what you actually found before
   proposing a plan.** Do not assume either state, and do not delete anything you did not first
   inspect and describe.

2. **This is scaffolding. It is not the application.** No visual polish, no business logic, no real
   backend calls in this first pass. The measure of success is that adding the first real feature
   requires no structural change.

3. **No backend integration in this pass.** The backend's authentication does not exist yet
   (Phase 2 is one sub-phase in) and its conversation and WebSocket endpoints do not exist yet
   (Phase 4 has not started). Everything runs against mocks. Building against a live backend today
   is impossible, and pretending otherwise produces code that must be rewritten.

4. **Never fabricate backend capability.** If this prompt does not state that an endpoint exists,
   it does not exist. Where you need a shape that has not been frozen, define it locally, name it
   provisional, and record it in `SYNC_CONTRACT.md` (Deliverable 8) as needing confirmation.

5. **Generated types are never edited by hand.** The generated file is build output. If it is wrong,
   the backend contract is wrong, and that is a conversation with the backend, not a local patch.

6. **Red before green.** For every piece of logic with real behaviour — above all the refresh
   queueing in Deliverable 4 — write the test, run it, *show it failing*, then implement. Paste the
   failing output. A test authored after the implementation it validates is not evidence.

7. **These project-wide constraints are carried verbatim from the backend repository and are not
   negotiable here:**
   - **No AI attribution in git history, ever.** Commit messages, pull request descriptions, tags
     and release notes must contain no reference to Claude, Anthropic, or any AI assistant: no
     co-authorship trailers naming an assistant, no session or conversation links, no generated-with
     badges. This overrides any attribution guidance your tooling supplies. It also applies to
     *describing* the rule — write neutrally, never the literal forbidden forms. This is coursework
     whose commit history is itself assessed.
   - **Conventional commits, one line, no body.** `feat:`, `fix:`, `chore:`, `docs:`, `test:`,
     `refactor:`. A single sentence under roughly 72 characters. Reasoning belongs in a code comment
     or in a document, not in a commit body.
   - **English** for code, identifiers, comments and documentation. French appears only in
     user-facing data such as skill labels and quality-criterion labels.
   - **No secret ever enters the repository.** No tokens, no keys, no passwords. `.env.local` is
     git-ignored; only `.env.example` is committed.

---

## THE BACKEND CONTRACT — every value you need, written out

The backend is Spring Boot 3.3.4 on Java 21, serving `http://localhost:8080` in local development.

### The OpenAPI document

Served by springdoc at **`http://localhost:8080/v3/api-docs`**, with Swagger UI at
`http://localhost:8080/swagger-ui/index.html`. This is the contract you generate types from.

**As of the backend commit this prompt was written against, the document contains exactly one
operation:** `GET /api/v1/health/status`, returning `HealthStatusResponse`
(`service`, `version`, `status`, `timestamp`, `profile`). It also publishes the `ErrorResponse`
schema and two security schemes that are **declared but not enforced**: `bearerAuth` (HTTP bearer,
JWT) and `serviceKey` (API key in header `X-Internal-Key`).

Generating types today therefore yields a nearly empty client. **That is expected**, and it is
exactly why you build against mocks. Regeneration is one command as each backend phase lands.

### The error envelope — frozen, exactly five fields

Every failure from the backend returns this shape and no other:

```json
{
  "timestamp": "2026-09-14T08:31:07.412Z",
  "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "message": "Customer 'a3f1...' was not found",
  "path": "/api/v1/credits"
}
```

> **Clients branch on `code`, never on `message`.**

That rule is quoted verbatim from the project's frozen contract. `message` is human-facing prose
that may be reworded at any time; `code` is the stable identifier. A `switch` on `message` is a bug
even when it currently works.

Statuses the backend's exception handler produces today: **400** (validation or an unmet use-case
precondition; `code` is `VALIDATION_FAILED` or a use-case-specific code), **404** (unknown route,
`ENDPOINT_NOT_FOUND`, or a missing resource), **409** (a business rule refused a well-formed
request), **500** (`INTERNAL_ERROR`, with a deliberately generic message).

**401 and 403 do not currently return this envelope.** Spring Security rejects at the filter chain,
before the dispatcher, and returns an empty body. Wiring them to the envelope is backend sub-phase
2.3 and has not shipped. **Your error-mapping utility must therefore tolerate a 401 or 403 with an
empty or non-JSON body** and synthesise a sensible client-side error rather than throwing while
parsing. This is the single most likely runtime surprise in the whole integration.

### The four roles — exactly these, no others

```
CUSTOMER    ADVISOR    SUPERVISOR    ADMIN
```

One role per account; the backend schema deliberately has no join table. What each role's UI is
expected to show, quoted from the project context:

| Role | Its view |
|---|---|
| `CUSTOMER` | Contract, invoices, tickets, chat with support, history |
| `ADVISOR` | Personal queue, live conversation, contextual customer panel, KB, business actions |
| `SUPERVISOR` | Live supervision, KPIs, alerts, escalations, Workforce Manager recommendations with validate/reject (human-in-the-loop), manual reassignment |
| `ADMIN` | Back-office: KB, catalogue, rules, ceilings, users; simulation studio; run comparison |

### The five WebSocket topics — frozen, verbatim

```
/topic/queue/{skill}        → arrivals and departures from a queue
/topic/conversation/{id}    → messages
/topic/supervision/kpi      → live KPIs
/topic/supervision/alerts   → SLA alerts
/topic/runs/{runId}         → simulation run progress
```

> Each subscription is filtered by role — an advisor only receives their own queue.

### The refresh rule — frozen

> **N concurrent 401s must trigger exactly one refresh.**

This is a frontend obligation recorded in the backend's own frozen contract. Deliverable 4 builds
and tests it.

### What does NOT exist in the backend yet

State this honestly in code comments wherever you build against it:

- **No authentication of any kind.** No login endpoint, no JWT filter, no user lookup. The backend's
  security configuration currently permits everything under the `dev` profile and denies everything
  otherwise. Backend Phase 2 delivers this; only its first sub-phase has landed.
- **No frozen auth path.** `/api/v1/auth/login` is a *proposal*, not a frozen contract — the string
  appears nowhere in the backend's contract documents. What is frozen is the `/api/v1` prefix.
  **Put every auth path behind a single exported constant** so one edit reconciles it later.
- **No conversation, queue, ticket, invoice, KB or simulation endpoints.** One endpoint exists:
  `GET /api/v1/health/status`.
- **No WebSocket implementation.** The backend's websocket package contains one file, a
  `package-info.java`. The dependency is present; no broker, no endpoint registration, no message
  mapping exists. Real-time ships in backend Phase 4, and its authentication in sub-phase 2.8.
- **CORS is not configured yet.** It is backend sub-phase 2.3. The backend has been told the allowed
  origin must be configurable rather than hardcoded; supply it `http://localhost:3000` when asked.

### What you must NOT call

**`/internal/**` is not yours.** Those seven endpoints are the tool API the Python AI service calls,
authenticated by a shared service key rather than a user JWT. The frontend must never call them.
They appear in the OpenAPI document; ignore them. If a generated client exposes them, do not wrap
them.

### Development accounts (for mock fixtures)

The backend seeds one account per role, all sharing the development password `CallVerse!Dev2026`:
`customer@callverse.local`, `advisor@callverse.local`, `supervisor@callverse.local`,
`admin@callverse.local`. Mirror these in your mock layer so a demo login works identically against
mocks and, later, against the real backend. They are development-only credentials and are already
public in the backend's README; they are not a secret, but do not invent others.

### The six modules the finished product must eventually contain

Customer portal · Advisor workstation · Supervision · Quality & XAI · Simulation studio ·
Back-office. The **advisor workstation** is the screen to show first at the defence; the
**simulation studio** is the one that produces the report's evidence.

---

## SKILLS — invoke these, do not freestyle

List `.claude/skills/` and report which exist before claiming to use any. If one is missing, say so
and say what you did instead.

- **`superpowers:writing-plans`** — first, before any file is created. The plan must show the serial
  foundation phase and the parallel phase separately.
- **`superpowers:test-driven-development`** — for the refresh-queueing logic above all. Red before
  green, with pasted failing output.
- **`superpowers:dispatching-parallel-agents`** — **only after** the foundation phase is complete
  and committed. See the ordering rule below.
- **`superpowers:verification-before-completion`** — at the end, with real pasted command output.

**Ordering rule, which is not negotiable.** The backend scaffold proved this and it applies
identically here: **the foundation is serial, the features are parallel.** Type generation, the API
client, the error mapping, the auth shell and the routing shell all touch shared files and must be
built one after another, by you, in this session. Only once they exist and are committed may you
dispatch parallel agents for the independent feature slices. Parallelising the foundation produces
merge conflicts in exactly the files everything else depends on.

**Do not use** any visual-design skill. Visual design is explicitly out of scope for this pass.

---

## DELIVERABLES, in order

### 1. State report and plan

Report what `callverse_frontend` actually contains right now, then produce the plan via
`superpowers:writing-plans`. Show it before executing.

### 2. Stack

- **Next.js, App Router, TypeScript in `strict` mode.** Non-negotiable.
- **Tailwind CSS plus a headless component set (shadcn/ui is the suggested default).** One line of
  justification: four genuinely distinct role UIs and a data-dense supervision dashboard are a lot
  of surface to hand-roll, and copy-in headless components avoid a heavyweight theming dependency.
  **This is a default, not a locked decision** — unlike the stack items above it, the project owner
  may override it. Say so in your plan rather than treating it as settled.
- ESLint and Prettier configured and passing.
- `.env.example` committed with `NEXT_PUBLIC_API_BASE_URL` and `NEXT_PUBLIC_WS_URL`; `.env.local`
  git-ignored.

### 3. Feature-sliced architecture

Mirror the backend's discipline: organise by feature, never by technical role.

```
src/
  app/
    (customer)/     layout.tsx + pages
    (advisor)/      layout.tsx + pages
    (supervisor)/   layout.tsx + pages
    (admin)/        layout.tsx + pages
    (public)/       login
    layout.tsx
  middleware.ts
  features/
    auth/            { api, components, hooks, types }
    conversations/   { api, components, hooks, types }
    workforce/       { api, components, hooks, types }
    simulation/      { api, components, hooks, types }
    quality/         { api, components, hooks, types }
    knowledge-base/  { api, components, hooks, types }
  shared/
    api-client/   ws-client/   auth/   ui/   config/
```

Each route group gets its own layout and a middleware guard. The backend's feature packages are
named `auth`, `conversation`, `kpi`, `quality`, `simulation` and `workforce`; the slice names above
match that vocabulary, with two deliberate differences to record in `SYNC_CONTRACT.md`:
`knowledge-base` has no backend *application* package (it is entity-level plus a tool endpoint), and
the backend's `kpi` slice will be consumed inside `workforce` and `simulation` rather than given a
slice of its own. Do not silently diverge further.

**Write one `README.md` inside `src/features/` stating the rule**: a slice owns its API calls, its
components, its hooks and its types; anything two slices need moves to `shared/`. A slice importing
from another slice's internals is the thing this structure exists to prevent.

### 4. Typed API client

- Generate types with **`openapi-typescript`** against `http://localhost:8080/v3/api-docs` into
  `src/shared/api-client/generated/callverse-api.d.ts`. Add an npm script (`npm run gen:api`).
  Commit the generated file so the build works without a running backend.
- A thin `fetch` wrapper in `src/shared/api-client/` that attaches the access token, sets
  `Content-Type`, and routes every failure through one error mapper.
- **One error-mapping utility, keyed on `code`, never on `message`.** It must handle: a well-formed
  five-field envelope; a 401 or 403 with an empty or non-JSON body (see the contract section — this
  is current backend behaviour, not a hypothetical); and a network failure with no response at all.
  Three cases, three tests.
- **The queued, de-duplicated refresh-on-401.** Requirement: *N concurrent 401s must trigger exactly
  one refresh call.* Build it with a module-level in-flight promise; every concurrent 401 awaits the
  same promise, then retries once. **Test it first and show the test failing**: fire five concurrent
  requests that all 401, assert the refresh endpoint was called exactly once, and assert all five
  retried and resolved. Also test that a failing refresh rejects all five and clears the in-flight
  promise, so the next 401 retries rather than awaiting a dead promise. That second case is the one
  that breaks in production.

<!-- ============ AUTH: OPTION D — THE RECOMMENDED PATH ============ -->

### 5. Authentication shell — Option D (direct calls)

**This section implements Option D, direct browser-to-backend calls.** Replace it wholesale with the
Option B block below if the project owner decides otherwise.

- The browser calls Spring Boot directly at `NEXT_PUBLIC_API_BASE_URL` and sends
  `Authorization: Bearer <token>`.
- **The access token is held in memory** — a module-level variable behind an accessor in
  `src/shared/auth/`, not in `localStorage` and not in `sessionStorage`. Write the reason in a
  comment: `localStorage` is readable by any script that achieves XSS, and survives the tab. Accept
  that an in-memory token is lost on refresh; a refresh-token call restores the session.
- `middleware.ts` guards the four route groups by role and redirects unauthenticated users to
  `/login`. **Write in a comment that this is a user-experience convenience and not a security
  boundary** — the backend is the only authority, and a middleware guard is trivially bypassed by
  calling the API directly.
- Because the token lives in the browser, **authenticated data fetching happens in Client
  Components.** Use Server Components for static shells and layouts. Do not build a Server Component
  that expects to read the token; it cannot.
- **CSRF stays disabled on the backend and that remains correct**, because this option introduces no
  cookie anywhere in the system. Record that reasoning in `SYNC_CONTRACT.md` — it is the condition
  under which the backend's current posture is justified, and it must be revisited if a cookie is
  ever introduced.

<!-- ============ AUTH: OPTION B — ONLY IF THE OWNER CHOSE BFF ============ -->

> **Replace §5 with this if Option B (BFF) was chosen.** Next.js Route Handlers proxy REST calls to
> Spring Boot server-to-server; the browser talks only to its own origin, so no CORS is needed for
> REST. The access token is held server-side and fronted by a session cookie that is `HttpOnly`,
> `Secure` and `SameSite=Lax` or `Strict`.
>
> **Introducing that cookie reopens CSRF, and it does so silently — nothing in the build fails.**
> The backend currently disables CSRF, and that is justified *only* because no cookie exists
> anywhere in the system. Under Option B that justification is void. You must add CSRF protection
> for state-changing requests: Next.js Server Actions carry an origin check of their own, but
> **Route Handlers do not**, and a `POST` Route Handler reached with an ambient cookie is the
> classic CSRF target. Record this in `SYNC_CONTRACT.md` as a backend-facing change request.
>
> **The WebSocket does not go through the BFF.** A thin proxy does not extend to a persistent
> bidirectional connection, so the browser still speaks STOMP to Spring Boot directly and still needs
> a credential the backend accepts on `CONNECT`. Under Option B that means a separate short-lived
> socket ticket, which is a second credential type with its own expiry and revocation story. Build
> the ticket fetch as an explicit, named module and document it — do not bury it inside the STOMP
> client.

<!-- ============ END AUTH FORK ============ -->

### 6. WebSocket / STOMP client

Build `src/shared/ws-client/` as a **Client-Component-only** module against the five frozen topics.

**Carry this honesty note into the module's header comment, verbatim:**

> The backend has no WebSocket implementation. Its websocket package contains a single
> `package-info.java`; there is no broker configuration, no message mapping and no endpoint
> registration. The `spring-boot-starter-websocket` dependency is present, which means the
> capability is available, not that it is built. Real-time ships in backend Phase 4 and its
> authentication in sub-phase 2.8. The five topic names below are frozen and safe to build against.
> The exact handshake — how the JWT is presented on `CONNECT`, whether a native WebSocket or a
> SockJS fallback is used, and the heartbeat configuration — cannot be known until those ship and
> must be confirmed then rather than guessed at now.

Requirements:

- A `Transport` interface with two implementations: a real STOMP client, and an in-memory fake that
  the tests and the mock environment use. Selecting between them is configuration, not a code edit.
- Typed subscribe helpers, one per topic, with the `{skill}`, `{id}` and `{runId}` parameters typed.
- **No message payload shape is documented for any topic.** Define provisional types, name them so
  the provisionality is obvious, and list every one in `SYNC_CONTRACT.md`.
- Reconnection with backoff, and subscriptions re-established on reconnect. Test this against the
  fake transport.
- STOMP runs only in a Client Component. Add a comment saying so where someone might otherwise try
  to call it from a Server Component.

### 7. Mock layer

**MSW (Mock Service Worker)**, seeded from the generated OpenAPI schema so mock responses cannot
drift from the contract silently.

- Handlers for the endpoints the walking skeleton needs, including a login handler that accepts the
  four seeded development accounts and returns a role-appropriate fake JWT.
- A handler that returns a real five-field error envelope, so the error mapper is exercised against
  the true shape.
- A handler that returns **401 with an empty body**, reproducing current backend behaviour.
- Enabled by an environment flag, on by default in development, and used by the Vitest suite.

### 8. `SYNC_CONTRACT.md`, at the root of `callverse_frontend`

The file that keeps the two repositories honest. It must list:

- The error envelope's five fields, and the branch-on-`code` rule.
- The five WebSocket topic names, verbatim.
- The four role names.
- Every endpoint this frontend expects to eventually exist, each marked **EXISTS** or **PROPOSED**.
  Today exactly one is `EXISTS`: `GET /api/v1/health/status`. Everything else is `PROPOSED`,
  including every auth path.
- Every provisional type you invented because no shape was frozen — above all the WebSocket payloads.
- The CORS origin this frontend needs: `http://localhost:3000`.
- Under Option D: a line recording that the backend's CSRF-disabled posture stays valid **because no
  cookie exists anywhere in the system**, and must be revisited if that changes.

**Add an instruction at the top of the file**: re-check this document whenever the backend's
`/v3/api-docs` output changes, and treat any difference as a contract change requiring a
conversation rather than a local workaround.

### 9. Walking skeleton

Symmetric to the backend's health endpoint, and just as disposable. **One real page per role**, four
in total, each: reachable only through its route group, guarded by the middleware, rendering data
fetched through the typed API client from MSW, and displaying a deliberately triggered error through
the error mapper to prove that path works end to end.

Keep them trivial. Write in each file that it is a walking skeleton to be deleted once real features
land, exactly as the backend says of its health endpoint.

### 10. Testing

**Vitest** for units. Mandatory coverage, each written red-first:

- The refresh queueing: five concurrent 401s produce exactly one refresh; all five retry; a failed
  refresh rejects all five and clears the in-flight promise.
- The error mapper: well-formed envelope; 401 with empty body; network failure with no response.
- The WebSocket client against the fake transport: subscribe, receive, reconnect with
  re-subscription.

**Playwright** stubs for three flows, chosen deliberately, one for each jury and one for both. Write
them as skipped specs with the real selectors and assertions sketched, so each becomes a live test by
deleting a `skip` as the underlying feature lands:

1. **A customer-to-advisor conversation** — 3iL: multi-role, real-time, the most demonstrative screen.
2. **The supervisor approving a Workforce Manager decision** — ESPRIT: the human-in-the-loop screen.
   Note in the spec that whether approval *gates* the action or merely *records* it is an open
   question on the backend side, and the assertion will differ depending on the answer.
3. **A simulation run comparison** — ESPRIT: run A versus run B with deltas. Assert that variance is
   displayed alongside the mean, not the mean alone. Presenting results without variance is one of
   the two most commonly failed items across both juries.

---

## NON-GOALS — do not do these

- **No visual design.** No custom palette, no typography system, no animation, no polish. Default
  component styling only.
- **No theming decisions** beyond adopting the default component set.
- **No business logic.** No routing algorithm, no priority score, no SLA computation. Those are
  backend concerns and the backend is their only authority.
- **No real backend calls.** Mocks only, this pass.
- **No state-management library** until something actually needs one. Server state belongs to the
  data-fetching layer; do not add a global store speculatively.
- **No authentication beyond the shell.** Token handling, guard, refresh queueing. No registration,
  no password reset, no profile management.
- **Do not call `/internal/**`.**

---

## QUALITY BAR

- **Verify before claiming.** Every "done" is backed by pasted command output.
- **Red before green**, with the failing run shown, for all logic in Deliverable 10.
- **No hand-edited generated types.**
- **No fabricated backend capability.** Where something does not exist, the code says so in a comment
  and `SYNC_CONTRACT.md` records it.
- **A new engineer can find where a feature goes** by reading `src/features/README.md` alone.
- **Every commit is one line**, conventional, and free of any automated tooling credit.

---

## FINAL STEP — `superpowers:verification-before-completion`

Paste real output for each. Do not summarise; paste.

1. `npm run build` — succeeding.
2. `npm run lint` and `npx tsc --noEmit` — both clean.
3. `ls -la src/shared/api-client/generated/` — the generated types exist, with a byte count.
4. `npm run test` — the full Vitest suite passing, with the count visible.
5. The refresh-queueing test's **failing** run from before it was implemented, then its passing run.
6. `npx playwright test --list` — the three stubs listed.
7. The four walking-skeleton pages actually rendering: for each role, the fetched value and the
   mapped error, as real output rather than a description.
8. `git log --oneline` — conventional one-line messages, no attribution of any kind.
9. `git status` — clean, with `.env.local` absent from it.

Then summarise briefly: which skills existed and were invoked; what `callverse_frontend` contained
before you started; which decisions you took that the project owner should review; and every item you
added to `SYNC_CONTRACT.md` that needs backend confirmation.
