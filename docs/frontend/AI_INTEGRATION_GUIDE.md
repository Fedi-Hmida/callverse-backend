# CallVerse — AI integration guide for the frontend

> **For:** Aziz (Next.js). **From:** Fedi (backend), with the AI work of Doua Omrani and Ines.
> **Date:** 2026-10-18. **Backend:** `main` @ `a161923`, 529 Java tests and 138 Python tests green.
> **Read with:** [BACKEND_INTEGRATION_GUIDE.md](./BACKEND_INTEGRATION_GUIDE.md) (auth, errors, roles, the
> first 28 routes, the live channel). This guide covers only what the AI integration adds or changes.
> **Source of truth:** `openapi.yaml` at the repository root (41 operations). If this guide and that
> file disagree, the file wins, and please tell me.
> **Start with §9:** it lists the screens to build, mapped to your files.

---

## 1. What is new for you

| Area | Ready? | What you build |
|---|---|---|
| **AI Advisor in the chat** | ✅ yes | Show AI replies with an "Assistant IA" badge (`aiGenerated`); nothing else changes in the chat |
| **AI escalation** | ✅ yes | Nothing new: the conversation becomes `ESCALATED` and the supervisor alert arrives as today |
| **Workforce recommendation** (supervisor) | ✅ yes | A "Proposition IA" card: reassign N advisors from pool A to pool B, with the reason and caveats |
| **Digital twin** (supervisor, admin) | ✅ yes | A "Jumeau numérique" screen: pick a scenario and a strategy, run, compare the KPIs side by side |
| **Conversation simulation** (supervisor, admin) | ✅ yes | Optional "Simulation" panel: launch a simulated customer against the AI Advisor and follow its state |
| **Quality grades by the AI** | ⏳ stored, no read route yet | Keep your quality screen mocked (the read route comes with FS-4) |
| **Approving / rejecting a workforce proposal** | ❌ not yet | Disable the button, label "validation humaine à venir" |

**13 new operations** since the 2026-10-10 changelog (41 in all), pinned by the build like the others. Seven are for you:
`requestWorkforceRecommendation`, `startConversationSimulation`, `getConversationSimulation`,
`runDigitalTwin`, `getDigitalTwinRun`, `compareDigitalTwinRuns`, `listScenarios`. The six others are
`/internal/**` routes that are **server-to-server only** (the AI service calls them; never call them
from the browser — a user token is refused there).

**One changed shape, additive only:** every message now carries `aiGenerated` (REST and live).

---

## 2. Run the backend with the AI

### What changed in the configuration

| Variable | Where | Required? | Purpose |
|---|---|---|---|
| `INTERNAL_SERVICE_KEY` | backend `.env` | **Yes — the backend refuses to start without it** (32+ bytes) | Key the AI service uses to call the backend's internal tools. Ask Fedi for the team value, or generate one with `openssl rand -base64 48` for a local run |
| `AI_SERVICE_BASE_URL` | backend `.env` | no (default `http://localhost:8000`) | Where the AI service listens |
| `AI_SERVICE_KEY` | backend `.env` **and** `ai-service/.env` | yes for the AI service (it refuses to start without it) | Key the backend sends to the AI service |
| `AI_ADVISOR_MODE` | backend `.env` | no (default `OFF`) | `OFF`: the AI never answers in live chats. `AUTONOMOUS`: it answers until a human advisor writes |
| `llm_api_key`, `pgvector_dsn`, `quality_model` | `ai-service/.env` | for the LLM features only | Never committed; ask the AI team |

### Three ways to run

1. **Backend only, as before.** Set `INTERNAL_SERVICE_KEY`; everything from the first guide works.
   The AI routes answer `503 WORKFORCE_UNAVAILABLE` (workforce, twin) and the chat simply has no AI
   replies. Good enough for most screen work.
2. **Backend + AI service, no LLM key** (`docker compose --profile ai up --build`). The workforce
   recommendation and the **digital twin work fully** (they use the trained policy and the queue
   simulator, no LLM). The AI Advisor, the simulator and the quality grader need an LLM key and stay
   silent without one.
3. **Everything** (team LLM key in `ai-service/.env`, `AI_ADVISOR_MODE=AUTONOMOUS`): the full demo.

Demo data (dev profile, `CALLVERSE_DEMODATA_ENABLED=true`) now also seeds three twin scenarios:
« Démo — journée calme » (low), « Démo — journée chargée » (medium) and « Démo — pic saturé » (saturated).

---

## 3. The AI in the chat

### How it behaves

- **`AI_ADVISOR_MODE=OFF` (default):** no AI reply ever. The chat is exactly as in the first guide.
- **`AUTONOMOUS`:** when a customer writes in an `ASSIGNED` or `ACTIVE` conversation, the AI answers a
  few seconds later, **until a human advisor writes in that conversation**. From the advisor's first
  message on, the AI stays silent for good (human takeover). The customer never gets two answers.
- If the customer sends several messages quickly, the AI answers the latest one (with the earlier
  ones as context). Nothing is lost.
- The AI's reply has `sender: "ADVISOR"` like a human reply. **Tell them apart with `aiGenerated`.**
- When the AI decides a supervisor must take over (low confidence, fraud, explicit request), it
  replies first ("je transmets votre demande à un superviseur"), then the conversation moves to
  `ESCALATED` and the supervisor receives the usual `ESCALATION_RAISED` alert. Same events as a human
  escalation; nothing new to code.
- If the AI service is down, slow or wrong, the customer's message is still saved and the human
  advisor answers as usual. There is no error to show.
- Privacy: IBANs, card numbers, e-mails and phone numbers are masked before anything reaches the AI
  provider. Your screens always show the real text (the database keeps it).

### `aiGenerated` — the one field to add

**REST** — `listMessages` (`GET /api/v1/conversations/{id}/messages`) and `postMessage`:

```json
{ "conversationId": "…", "messages": [
  { "id": 41, "conversationId": "…", "sender": "CUSTOMER", "content": "Ma carte est bloquée", "sentAt": "…", "aiGenerated": false },
  { "id": 42, "conversationId": "…", "sender": "ADVISOR", "content": "Je vérifie votre carte tout de suite.", "sentAt": "…", "aiGenerated": true }
] }
```

**Live** — `/topic/conversation/{id}`, `MESSAGE_POSTED` (still `schemaVersion: 1`; the field is added,
nothing removed):

```json
{ "schemaVersion": 1, "type": "MESSAGE_POSTED", "occurredAt": "…", "conversationId": "…", "status": "ACTIVE",
  "messageId": 42, "sender": "ADVISOR", "content": "Je vérifie votre carte tout de suite.", "sentAt": "…",
  "aiGenerated": true }
```

On `STATUS_CHANGED`, `aiGenerated` is `null` like the other message fields.

**UX rules (please follow them — they are also a legal transparency point):**

1. Every message with `aiGenerated: true` shows a visible "Assistant IA" badge, for the customer, the
   advisor and the supervisor.
2. In the advisor's view, an AI reply in their conversation is a hint that the customer has been
   talking to the AI before they took over; show it in the transcript, never hide it.
3. Do not offer an "AI on/off" switch in the UI: the mode is a server setting.

### Event order is now guaranteed

Frames on one connection now arrive in the order the server published them. Concretely: the
advisor's first reply sends `MESSAGE_POSTED`, then `STATUS_CHANGED` (`ASSIGNED` → `ACTIVE`), and you
receive them in that order. (Before, under load, the status could arrive first.) You can apply events
as they come.

---

## 4. Workforce recommendation (supervisor)

`requestWorkforceRecommendation` — `POST /api/v1/supervision/workforce/recommendations`, no body.
SUPERVISOR or ADMIN; others get 403.

The backend reads the live queues and advisor capacity, asks the trained policy (PPO) for a
proposal, records it, and returns it. **It never moves anyone**: it is a proposal for a human.

```json
{
  "decisionId": 17,
  "generatedAt": "2026-10-18T09:30:00Z",
  "type": "REASSIGN",
  "fromPool": "CARDS",
  "toPool": "ACCOUNTS",
  "count": 1,
  "reason": "PPO propose de renforcer ACCOUNTS (15 clients, 120 s d'attente) depuis CARDS. …",
  "expectedGain": {},
  "requiresApproval": true,
  "observation": { "…": "the queues, waits and capacity the proposal was computed from" },
  "caveats": ["…", "…"]
}
```

- `type` is `NONE` (nothing to change; `fromPool`, `toPool`, `count` are null) or `REASSIGN`.
- Pools are the four skills: `ACCOUNTS`, `CARDS`, `CREDIT`, `FRAUD`.
- `caveats` lists the limits of the inputs (for example "SLA not measured yet, neutral value used").
  **Show them** under the proposal, in small text.
- `expectedGain` is empty today; do not show a gain.
- `requiresApproval` is always `true`. There is **no approve/reject route yet**: show the proposal,
  disable the action button with "validation humaine à venir".
- `503 WORKFORCE_UNAVAILABLE`: the AI service is unavailable. Show "Proposition indisponible, réessayer".

**Screen:** a card on the supervisor dashboard with a "Demander une proposition" button, the proposal
in one sentence ("Déplacer 1 conseiller de CARTES vers COMPTES"), the reason, the caveats. Full spec in §9.3.

---

## 5. Digital twin (supervisor, admin)

The twin replays a simulated day of the contact centre (the four queues) under a **scenario** (load,
duration, staff) and a **control strategy**, and keeps the KPIs, so strategies can be compared on the
same simulated customers.

### Routes

| operationId | Method and path | Purpose |
|---|---|---|
| `listScenarios` | `GET /api/v1/scenarios` | The scenarios to choose from |
| `runDigitalTwin` | `POST /api/v1/twin-runs` | Run one scenario × strategy × seed (synchronous, < 1 s) |
| `getDigitalTwinRun` | `GET /api/v1/twin-runs/{runId}` | One run |
| `compareDigitalTwinRuns` | `GET /api/v1/twin-runs?scenarioId=…` | Every completed run of a scenario, for the comparison |

All four: SUPERVISOR or ADMIN.

### Scenarios — `listScenarios`

```json
[
  { "id": "…", "name": "Démo — journée calme", "loadProfile": "LOW", "durationMinutes": 480, "advisorCount": 12 },
  { "id": "…", "name": "Démo — journée chargée", "loadProfile": "MEDIUM", "durationMinutes": 480, "advisorCount": 12 },
  { "id": "…", "name": "Démo — pic saturé", "loadProfile": "SATURATED", "durationMinutes": 480, "advisorCount": 12 }
]
```

There is no scenario editor yet; scenarios come from the demo data or the database.

### Run — `runDigitalTwin`

```json
{ "scenarioId": "…", "strategyCode": "RL_PPO_V1", "seed": 7 }
```

| `strategyCode` | Label to show |
|---|---|
| `STATIC_FIFO` | « Statique (aucune réaffectation) » |
| `THRESHOLD` | « Seuil (renfort au-delà de 3 clients en file) » |
| `RL_PPO_V1` | « IA — apprentissage par renforcement (PPO) » |

`seed` (0 or more) fixes the simulated customers: **use the same seed for every strategy** you want to
compare. Response `201` (with a `Location` header):

```json
{
  "runId": "…", "scenarioId": "…", "strategyCode": "RL_PPO_V1", "seed": 7,
  "status": "COMPLETED", "errorMessage": null,
  "startedAt": "2026-10-18T09:31:00Z", "endedAt": "2026-10-18T09:31:00Z",
  "kpis": {
    "totalConversations": 1408,
    "slaRatio": 0.4259,
    "abandonRatio": 0.1890,
    "avgWaitSeconds": 201.2,
    "p95WaitSeconds": 512.4
  }
}
```

| KPI | Meaning | Display |
|---|---|---|
| `totalConversations` | customers who arrived during the simulated day | integer |
| `slaRatio` | answered within the SLA **over all arrivals** (abandons count as misses) | percentage, 1 decimal |
| `abandonRatio` | customers who hung up | percentage, 1 decimal |
| `avgWaitSeconds`, `p95WaitSeconds` | average and 95th-percentile wait | `m:ss` |

Errors (usual envelope):

| Status | `code` | When | UI |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | unknown strategy, negative seed, or a scenario beyond the twin's limits (1–1440 minutes, at most 50 advisors per skill) | message under the form |
| 404 | `RESOURCE_NOT_FOUND` | unknown scenario | "Scénario introuvable" |
| 409 | `SIMULATION_RUN_ALREADY_EXISTS` | this scenario + strategy + seed already completed — the result is in the comparison | "Déjà calculé" and show the existing run |
| 503 | `WORKFORCE_UNAVAILABLE` | AI service unavailable; the run is kept `FAILED` | "Jumeau indisponible" + a "Réessayer" button: **the same request works again** once the service is back |

### Compare — `compareDigitalTwinRuns`

Returns the completed runs of the scenario, ordered by strategy then seed (same objects as above).
Group by `strategyCode`, average over seeds if there are several.

**Screen suggestion (full spec in §9.4):** scenario selector → seed input (default 7) → "Lancer les 3 stratégies" (three
POSTs; a 409 just means "already there") → a grouped bar chart per KPI (SLA, abandons, attente
moyenne) with one bar per strategy, and a table below.

**What the numbers say (measured on the real AI image, 12 advisors, 8 h, seeds 7 and 8):** PPO has
the best SLA at every load, but more abandons at low and medium load. That trade-off is the honest
result — show both KPIs, never SLA alone.

| Load | Strategy | SLA | Abandons | Avg wait |
|---|---|---|---|---|
| Low | Static | 66.5 % | 1.0 % | 1:31 |
| Low | Threshold | 68.0 % | 0.1 % | 1:11 |
| Low | PPO | **69.8 %** | 5.9 % | 1:43 |
| Medium | Static | 31.3 % | 12.9 % | 3:50 |
| Medium | Threshold | 27.0 % | 9.1 % | 3:30 |
| Medium | PPO | **42.6 %** | 18.9 % | 3:21 |

---

## 6. Conversation simulation (supervisor, admin) — optional screen

A simulated customer (profile, patience, objective) talks to the AI Advisor in an isolated,
simulation-only conversation. It needs the LLM key; without it the run ends `FAILED`.

`startConversationSimulation` — `POST /api/v1/simulation-runs` → `202` with a `Location` to poll:

```json
{ "scenarioId": "…", "strategyCode": "STATIC_FIFO", "seed": 101, "profile": "IMPATIENT",
  "objective": "Débloquer ma carte", "patience": 60, "satisfaction": 50, "maxTurns": 6,
  "customerSegment": "MASS", "tenureMonths": 24 }
```

- `profile`: `CALME`, `IMPATIENT`, `INSATISFAIT`, `EXIGEANT`, `OFFRE`, `CHURN`.
- `patience`, `satisfaction`: 0–100. `maxTurns`: 1–50.
- `customerSegment`: `MASS`, `AFFLUENT`, `PRIVATE`, `PROFESSIONAL`.

`getConversationSimulation` — `GET /api/v1/simulation-runs/{runId}` (poll every 1–2 s until `status`
is `COMPLETED` or `FAILED`):

```json
{ "runId": "…", "conversationId": "…", "status": "COMPLETED", "conversationStatus": "RESOLVED",
  "messageCount": 4, "errorMessage": null, "startedAt": "…", "endedAt": "…" }
```

The simulated conversation never appears in the live supervisor list or the live KPIs. Its id is
separate from the twin's: a twin run id answers 404 here and the other way round.

---

## 7. New and reused error codes

| Status | `code` | Where |
|---|---|---|
| 503 | `WORKFORCE_UNAVAILABLE` | workforce recommendation, digital twin |
| 409 | `SIMULATION_RUN_ALREADY_EXISTS` | digital twin, conversation simulation (same scenario + strategy + seed) |
| 400 | `VALIDATION_FAILED` | invalid twin request or scenario beyond limits |
| 404 | `RESOURCE_NOT_FOUND` | unknown scenario or run |

---

## 8. TypeScript types

```ts
export type MessageView = {
  id: number; conversationId: string; sender: 'CUSTOMER' | 'ADVISOR' | 'SYSTEM';
  content: string; sentAt: string; aiGenerated: boolean;
};

export type ConversationLiveEvent = {
  schemaVersion: 1; type: 'MESSAGE_POSTED' | 'STATUS_CHANGED'; occurredAt: string;
  conversationId: string; status: ConversationStatus;
  messageId: number | null; sender: 'CUSTOMER' | 'ADVISOR' | 'SYSTEM' | null;
  content: string | null; sentAt: string | null; aiGenerated: boolean | null;
};

export type Skill = 'ACCOUNTS' | 'CARDS' | 'CREDIT' | 'FRAUD';

export type WorkforceRecommendation = {
  decisionId: number; generatedAt: string; type: 'NONE' | 'REASSIGN';
  fromPool: Skill | null; toPool: Skill | null; count: number | null;
  reason: string; expectedGain: Record<string, number>; requiresApproval: true;
  observation: unknown; caveats: string[];
};

export type Scenario = {
  id: string; name: string; loadProfile: 'LOW' | 'MEDIUM' | 'SATURATED';
  durationMinutes: number; advisorCount: number;
};

export type TwinStrategy = 'STATIC_FIFO' | 'THRESHOLD' | 'RL_PPO_V1';

export type TwinRun = {
  runId: string; scenarioId: string; strategyCode: TwinStrategy; seed: number;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'; errorMessage: string | null;
  startedAt: string | null; endedAt: string | null;
  kpis: { totalConversations: number; slaRatio: number; abandonRatio: number;
          avgWaitSeconds: number; p95WaitSeconds: number } | null;
};
```

Prefer generating them from `openapi.yaml` (first guide, "Generate your types") and keep these only
for the live payloads.

---

## 9. Screens to build, file by file

This section maps every AI screen to your code (`callverse-frontend`, branch `fedi/updates` @
362290b). For each one it gives where the change goes, the data, the states and when it is done.
Reuse what you already have: `AiSlotView` for the states, `apiFetch` for the calls, `cv-*` classes
for the look.

### 9.0 Four fixes in your existing AI seams (do these first)

Your AI seams were written before the backend existed. Four things no longer match:

| # | File | Today | Change to |
|---|---|---|---|
| 1 | `lib/advisor.ts` → `messageFromEvent`, and `components/ConversationPanel.tsx` (the `useTopic` handler) | both rebuild a `Message` from the live event and **drop `aiGenerated`** | copy `aiGenerated: event.aiGenerated === true`. Otherwise the badge shows after a reload but not live |
| 2 | `lib/contracts.ts` → `ConversationEvent` | no `aiGenerated` | add `aiGenerated: boolean \| null`. Regenerate `callverse-api.d.ts` from `openapi.yaml`; `Message.aiGenerated` is then a **required** boolean |
| 3 | `lib/ai.ts` → `adaptWorkforce` | reads snake_case (`from_pool`, `to_pool`, `expected_gain`) and no `caveats` | the backend sends camelCase: `type`, `fromPool`, `toPool`, `count`, `reason`, `caveats[]`, `decisionId`, `generatedAt`, `requiresApproval`. Add `caveats` to `WorkforceViewModel` |
| 4 | `lib/aiRequests.ts` → `AI_ENDPOINTS.suggestion` | `POST /conversations/{id}/suggestion` | **this route does not exist.** The AI answers the customer itself (`AUTONOMOUS`). Keep `AI_FEATURES.suggestion = false`; the slot keeps saying « Bientôt disponible » |

**`AI_FEATURES` after this guide:**

```ts
export const AI_FEATURES: Record<AiFeature, boolean> = {
  suggestion: false, // no route: the AI replies directly (§3)
  workforce: true,   // requestWorkforceRecommendation
  quality: false,    // no read route yet (§11)
  simulation: true,  // twin + conversation simulation
};
```

**A caveat on `useAiCapability`.** `/health/status` reports the *backend's* health. It has no field
for the AI service, so `UP` does not mean the AI answers. Keep the hook as it is (it only
downgrades), and treat `503 WORKFORCE_UNAVAILABLE` from a call as the real "AI down" signal: map it
to the slot's `error` state with a « Réessayer » button.

### 9.1 « Assistant IA » badge in every transcript — all roles

**Where:** `components/ChatThread.tsx`, inside the message loop. It serves the customer
(`app/client`), the advisor (`components/advisor/ChatPanel.tsx`) and the supervisor
(`ConversationPanel role="SUPERVISOR"`), so one change covers all three.

**Data:** `message.aiGenerated` (REST `listMessages`, live `MESSAGE_POSTED`). No new call.

**Render:** in `cv-message-name`, next to the sender label, when `message.aiGenerated` is true:

```
 Conseiller · Assistant IA            10:42
┌──────────────────────────────────────────┐
│ Je vérifie votre carte tout de suite.     │
└──────────────────────────────────────────┘
```

- Badge text « Assistant IA », in the same pill style as the « Généré par l'IA » badge in
  `AiSlotView`. Do not rely on colour alone: give it text and
  `aria-label="Message rédigé par l'assistant IA"`.
- For the customer, replace the advisor's name with « Assistant IA »: no person wrote it.
- For the advisor, keep AI messages in the transcript, at the same weight as human ones. They show
  what the customer was told before the advisor took over.

**Done when:**
- [ ] An AI reply shows the badge in the customer, advisor and supervisor views, both after a reload
      and when it arrives live.
- [ ] A human advisor reply never shows it (`aiGenerated: false`).
- [ ] `STATUS_CHANGED` events (with `aiGenerated: null`) change nothing in the transcript.

### 9.2 Advisor workspace — human takeover notice

**Where:** `components/advisor/ChatPanel.tsx`, above the composer.

**Rule:** if the conversation contains at least one `aiGenerated` message and no human advisor message
yet, show a one-line notice:

```
ⓘ L'assistant IA a répondu à ce client. Votre premier message reprend la main : l'IA ne répondra plus.
```

It disappears once the advisor sends their first message. Derive it from `messages`; there is no call
and no server state to read.

**Leave the « Suggestion de réponse » slot as it is** (`AiSlotView` + `SuggestionCard`): with
`AI_FEATURES.suggestion = false` it renders « Bientôt disponible », which is true.

**Done when:**
- [ ] The notice shows only when the AI has spoken and no human has.
- [ ] It disappears right after the advisor's first send, without a reload.

### 9.3 Supervisor dashboard — workforce proposal card

**Where:** `app/supervisor/page.tsx`, with a new `components/supervisor/WorkforceCard.tsx` placed
after `<KpiTiles>` and before the two-column row. Add a sidebar item in
`components/shell/navItems.ts` → `Superviseur`: `{ id: "workforce", label: "Proposition IA", icon:
Sparkles }`, and give the card `id="workforce"` (the sidebar scrolls to section ids).

**Data:** `requestWorkforceRecommendation`, `POST /supervision/workforce/recommendations`, no body
(§4). Call it only when the button is clicked, never on load or on a timer: the backend records each
call.

**States (inside `AiSlotView`, `feature="workforce"`):**

| State | What shows |
|---|---|
| idle | title « Proposition de répartition », button « Demander une proposition » |
| loading | the slot's loader; button disabled |
| ready, `type: "REASSIGN"` | « Déplacer **1** conseiller de **Cartes** vers **Comptes** » (use `skillLabel` from `lib/presentation.ts`), `reason` below, then `caveats` in small grey text |
| ready, `type: "NONE"` | « Aucun changement recommandé pour l'instant », `reason`, `caveats` |
| error 503 | « Proposition indisponible » + « Réessayer » |
| error 403 | hide the card (the user is not a supervisor) |

Always show a disabled « Appliquer » button labelled « Validation humaine à venir »: there is no
approve route yet (§11). Show `generatedAt` as « il y a 2 min ».

```
┌ Proposition de répartition ─────────── Généré par l'IA ┐
│ Déplacer 1 conseiller de Cartes vers Comptes            │
│ PPO propose de renforcer COMPTES (15 clients, 120 s…)   │
│ · SLA non mesuré, valeur neutre utilisée                │
│ [Appliquer — validation humaine à venir]  [Redemander]  │
└─────────────────────────────────────────────────────────┘
```

**Done when:**
- [ ] One click gives one proposal; a second click gives a new one.
- [ ] `NONE` and `REASSIGN` both read as a sentence, with the caveats visible.
- [ ] With the AI service stopped, the card shows the 503 message, and « Réessayer » works once the
      service is back.

### 9.4 Digital twin page — supervisor and admin

This is the most visible AI screen, and it works without an LLM key.

**Where:** a new route `app/supervisor/twin/page.tsx` (in `AppShell role="Superviseur"`). Add a
sidebar item `{ id: "twin", label: "Jumeau numérique", icon: FlaskConical }` that links to it. For
the admin, add the tab « Jumeau numérique » to `tabs` in `app/admin/page.tsx` and to
`itemsByRole["Analyste / Admin"]`, and render the same component. Put the screen in
`components/twin/TwinLab.tsx` so both pages share it.

**Data (§5):** `listScenarios` on load; `runDigitalTwin` three times on « Lancer »;
`compareDigitalTwinRuns?scenarioId=` after the runs and when the scenario changes.

**Layout:**

```
┌ Jumeau numérique ──────────────────────────────────────────────────┐
│ Scénario [Démo — journée chargée ▾]   Graine [ 7 ]   [Lancer les 3] │
├────────────────────────────────────────────────────────────────────┤
│ SLA            ███████ 31,3 %  ██████ 27,0 %  █████████ 42,6 %     │
│ Abandons       ███ 12,9 %      ██ 9,1 %       ████ 18,9 %          │
│ Attente moy.   3:50            3:30           3:21                 │
│                Statique        Seuil          IA (PPO)             │
├────────────────────────────────────────────────────────────────────┤
│ Stratégie | Graines | Clients | SLA | Abandons | Attente | P95      │
│ …one row per strategy, averaged over seeds…                        │
└────────────────────────────────────────────────────────────────────┘
 ⓘ L'IA améliore le SLA mais augmente les abandons à charge faible et moyenne.
```

**Behaviour:**
- « Lancer les 3 » sends the three POSTs **one after the other** (same `scenarioId` and `seed`, one
  per `strategyCode`), then reloads the comparison. Each run takes under a second.
- `409 SIMULATION_RUN_ALREADY_EXISTS` is a success: show « Déjà calculé », not an error.
- `503 WORKFORCE_UNAVAILABLE` on one strategy: mark that strategy « Indisponible — Réessayer » and
  keep the others. The same request works on retry.
- `400 VALIDATION_FAILED`: show `message` under the form (a negative seed, for example).
- Charts: show SLA and abandons **together**, never SLA alone (§5 explains why). Percentages to one
  decimal, waits as `m:ss`, French number format.
- Empty comparison: « Aucun résultat pour ce scénario. Lancez les trois stratégies. »
- Use the strategy labels from §5, not the codes.

**Done when:**
- [ ] On a fresh database: pick « journée chargée », seed 7, « Lancer » → three bars per KPI, and the
      numbers match the table in §5.
- [ ] « Lancer » again with the same seed shows « Déjà calculé » and the same chart.
- [ ] Seed 8 adds a second run per strategy, and the table averages them.
- [ ] Advisors and customers never see the menu item; a direct visit gets 403 → « Accès refusé ».

### 9.5 Conversation simulation panel — optional

It only produces something with the team's LLM key, so build it last.

**Where:** a second block on the twin page: « Simuler un client ».

**Form:** scenario (from `listScenarios`), profile (`CALME` … `CHURN`, with French labels), objective
(text), patience and satisfaction (0–100 sliders), max turns (1–50, default 6). By default send
`strategyCode: "STATIC_FIFO"`, a seed and `customerSegment: "MASS"` (§6).

**Flow:** `startConversationSimulation` → `202` → poll `getConversationSimulation` every 1.5 s until
`COMPLETED` or `FAILED`. Stop polling when the user leaves the page (`AbortController`, as
`useSuggestion` does).

| Status | What shows |
|---|---|
| `PENDING` / `RUNNING` | loader, « Simulation en cours… » |
| `COMPLETED` | final conversation status (`RESOLVED` → « Résolue », `ABANDONED` → « Abandonnée »), `messageCount`, duration |
| `FAILED` | `errorMessage` (without the LLM key: « Service IA indisponible ») |
| 409 | « Déjà simulé avec cette graine » — change the seed |

`adaptSimulationTurn` in `lib/ai.ts` describes one *turn*. The backend returns the *run*, so this
panel does not use it.

**Done when:**
- [ ] With the LLM key, a run reaches `COMPLETED` and shows its outcome.
- [ ] Without it, the run reaches `FAILED` with a readable message, and the page stays usable.

### 9.6 What not to build

| Do not build | Why |
|---|---|
| A switch to turn the AI on or off | `AI_ADVISOR_MODE` is a server setting (§3) |
| Any call to `/internal/**` or to the AI service | the browser only talks to `/api/v1`; those routes refuse user tokens |
| A quality-grade screen | no read route yet; keep `AI_FEATURES.quality = false` |
| An "approve proposal" action | no route yet; a disabled button only |
| A scenario editor | no route yet; use the three demo scenarios |

### 9.7 Screens per role

| Role | Screens touched |
|---|---|
| Customer | 9.1 badge |
| Advisor | 9.1 badge, 9.2 takeover notice |
| Supervisor | 9.1 badge (open conversation), 9.3 workforce card, 9.4 twin page, 9.5 simulation |
| Admin | 9.4 twin tab, 9.5 simulation |

---

## 10. Build order

0. **The four seam fixes** (§9.0) — an hour of work, and everything below depends on them.
1. **"Assistant IA" badge** on messages (`aiGenerated`, §9.1, plus the takeover notice §9.2) — small, visible, and a transparency
   requirement.
2. **Digital twin screen** (§9.4) — the most visible AI feature for the jury, and it works without an LLM
   key.
3. **Workforce proposal card** on the supervisor dashboard (§9.3) — also works without an LLM key.
4. **Conversation simulation panel** (§9.5) — optional, needs the LLM key to show anything.

---

## 11. Not ready yet — mock these

| Missing | Why | Mock with |
|---|---|---|
| Approving / rejecting a workforce proposal | needs a schema decision (rejection not storable yet) | disabled button |
| Reading the AI quality grades | the read route comes with the quality phase (FS-4) | JSON fixtures |
| Creating or editing scenarios | no editor yet | the three demo scenarios |
| Advisor reply *suggestions* (copilot) | the AI answers directly in `AUTONOMOUS`; a suggestion-only mode is planned | your static `SuggestionCard` |
| A recorded demo with a real LLM | needs the team key | — |

---

## 12. Gotchas

1. **The backend now needs `INTERNAL_SERVICE_KEY` to start**, even if you never run the AI.
2. **Never call `/internal/**`** from the browser; it is for the AI service only and refuses user tokens.
3. **`aiGenerated` is the only way to recognise an AI reply** (`sender` is `ADVISOR` for both).
4. **A twin 409 is good news**: the result already exists; read it from the comparison.
5. **A twin 503 is retryable** with the exact same request.
6. **Show abandons next to SLA** on the twin screen: PPO's better SLA comes with more abandons.
7. **No AI reply in the chat?** Check `AI_ADVISOR_MODE` and that a human advisor has not written yet.

---

## 13. Changelog

| Date | Change |
|---|---|
| 2026-10-18 | §9 added: the AI screens mapped to the frontend code, four seam fixes, the `AI_FEATURES` values |
| 2026-10-18 | AI integration merged into `main`: AI Advisor in live chats (OFF/AUTONOMOUS, human takeover, escalation, masking), `aiGenerated` on messages (REST and live), ordered live events, workforce recommendation, digital twin (scenarios, runs, comparison), conversation simulation, `INTERNAL_SERVICE_KEY` now required |
