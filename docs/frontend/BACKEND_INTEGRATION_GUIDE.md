# CallVerse backend — frontend integration guide

> **For:** the Next.js developer. **From:** Fedi (backend). **Date:** 2026-10-02.
> **Backend version:** commit `5812921` on `main`, 344 tests green.
> **Source of truth:** `openapi.yaml` at the repository root. If this guide and that file ever disagree,
> the file wins, and please tell me.

---

## 1. Status at a glance

| Area | Ready to integrate? | Notes |
|---|---|---|
| Login, "who am I", roles | ✅ yes | JWT bearer, 1 hour |
| Error handling | ✅ yes | one envelope everywhere, branch on `code` |
| Advisor workspace: customer lookup, accounts, cards, transactions, outages, knowledge base, tickets, card blocking | ✅ yes | 9 operations |
| **Conversation core**: queue, take a call, chat, resolve, abandon, escalate | ✅ yes | 10 operations, new this week |
| Live supervision KPIs | ✅ yes | REST + live topic |
| **Live channel (STOMP/WebSocket)**: queue, conversation, KPI, alerts | ✅ yes | 4 of 5 topics live |
| Simulation studio (runs, experiment KPIs, comparison) | ❌ not yet | mock it; AI phase, later |
| Quality review screen | ❌ not yet | next backend phase (FS-4) |
| Customer self-service (a customer opening their own chat, their own accounts) | ❌ not yet | blocked on a schema change |
| **Account administration**: create users, change roles, block / unblock | ✅ yes | 6 operations, new 2026-10-03 |
| Back-office CRUD (KB, products, advisor skills, rules) | ❌ not yet | mock it |
| Advisor presence (available/break/offline) and supervisor reassignment | ❌ not yet | mock it |

**28 REST operations** in total, every one with a fixed `operationId` that the build pins: a renamed
route or field breaks the backend build before it can break you.

---

## 2. Run the backend and connect

### Base URLs

| What | URL |
|---|---|
| REST API | `http://localhost:8080/api/v1` |
| Live channel | `ws://localhost:8080/ws` (STOMP over native WebSocket, no SockJS) |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI document | `http://localhost:8080/v3/api-docs` |
| Health (no token) | `http://localhost:8080/api/v1/health/status` |

### CORS

Allowed origin by default: **`http://localhost:3000`** (Next.js dev server). Allowed methods: GET,
POST, PUT, PATCH, DELETE. Allowed headers: `Authorization`, `Content-Type`. No cookies
(`credentials: 'omit'`). Another origin? Ask me: it is one setting (`CORS_ALLOWED_ORIGINS`).

### Development accounts

One per role, all with the password **`Admin111***`**. They are for local development and the demo only.

| Email | Role | In the demo scenario |
|---|---|---|
| `customer@callverse.local` | CUSTOMER | **Amina Haddad** (customer `DEMO-00418`) |
| `advisor@callverse.local` | ADVISOR | **Karim Benali**, holds ACCOUNTS, CARDS, CREDIT, FRAUD, max 2 calls at once |
| `supervisor@callverse.local` | SUPERVISOR | Sarah |
| `admin@callverse.local` | ADMIN | |

### Demo data

Start the backend with the `dev` profile and `CALLVERSE_DEMODATA_ENABLED=true`. You then get the
scenario below; it is loaded once and never in production.

- **Amina Haddad** (`DEMO-00418`, Marseille, AFFLUENT): two accounts and card `4242`, with two
  suspicious PENDING payments from "ONLINE STORE VILNIUS" and one REJECTED payment in Marseille. She
  has one **ACTIVE** conversation assigned to Karim.
- **Lucas Martin** (`DEMO-00512`) waits in the ACCOUNTS queue and **Sofia Rossi** (`DEMO-00733`) in
  the CARDS queue, so "take next" works immediately.
- **Outages:** one ONLINE_BANKING outage in Lyon, none in Marseille.
- **Knowledge base:** articles on blocking a card ("opposition"), card limits and disputing a payment.

### Generate your types

```bash
npx openapi-typescript http://localhost:8080/v3/api-docs -o src/shared/api-client/generated/callverse-api.d.ts
```

Regenerate whenever I announce a contract change (§12).

---

## 3. Authentication

### Log in — `login`

`POST /api/v1/auth/login` (no token)

```json
{ "email": "advisor@callverse.local", "password": "Admin111***" }
```

**200**

```json
{ "token": "eyJhbGciOiJIUzUxMiJ9...", "expiresAt": "2026-10-02T15:00:00Z", "role": "ADVISOR" }
```

- **401 `INVALID_CREDENTIALS`:** wrong email or wrong password. The two are deliberately
  indistinguishable, so show one message for both.
- **Every other call** sends `Authorization: Bearer <token>`.
- **Lifetime:** 1 hour. **There is no refresh endpoint**, so on `401 UNAUTHENTICATED` send the user
  back to login. Use `expiresAt` to warn before it expires.
- **Storage:** keep the token in memory (or `sessionStorage` at most), never in a cookie or
  `localStorage`. Never log it.

### Who am I — `getCurrentUser`

`GET /api/v1/auth/me` returns **200**:

```json
{ "userId": "7d3f0c52-6a4e-4b8e-9d1f-2c5a8e9b0f13", "email": "advisor@callverse.local", "role": "ADVISOR" }
```

Route by `role` after login:

| Role | Screen |
|---|---|
| ADVISOR | advisor workstation |
| SUPERVISOR | live supervision |
| ADMIN | back office |
| CUSTOMER | customer portal |

---

## 4. Errors

Every failure, on every route, has the same body:

```json
{
  "timestamp": "2026-10-02T09:01:12.412Z",
  "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "message": "Conversation '...' was not found",
  "path": "/api/v1/conversations/..."
}
```

**Branch on `code`, never on `message`**: messages are for humans and will be reworded.

| Status | Meaning for the UI | Codes you will see |
|---|---|---|
| 400 | Fix the input | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `CONVERSATION_CUSTOMER_MISMATCH` |
| 401 | Not logged in, or the token expired: go to login | `UNAUTHENTICATED`, `INVALID_CREDENTIALS` |
| 403 | Logged in but not allowed: show "not permitted", **do not** re-login | `ACCESS_DENIED` |
| 404 | It doesn't exist, **or it isn't yours** (deliberately the same answer) | `RESOURCE_NOT_FOUND`, `ADVISOR_PROFILE_NOT_FOUND`, `SLA_POLICY_NOT_FOUND`, `ENDPOINT_NOT_FOUND` |
| 405 / 415 | Wrong method or content type (a bug on our side) | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` |
| 409 | The business state forbids it now: show the message and refresh the data | `INVALID_STATE_TRANSITION`, `ADVISOR_UNAVAILABLE`, `SELF_LOCKOUT`, `EMAIL_ALREADY_USED` |
| 500 | Our bug: show a generic error | `INTERNAL_ERROR` |

---

## 5. Roles and ownership — who may do what

The role on a route is the first check. Some routes add an **ownership** check:

- **A customer** reaches a conversation only if it is theirs.
- **An advisor** reaches a conversation only if it is **assigned to them**. A conversation still in
  the queue belongs to no advisor until one takes it.
- **Supervisors and admins** see every live conversation.
- **Not yours → 404** (as if it did not exist). **Yours to see, but not this action → 403.**

*Staff* below means ADVISOR, SUPERVISOR or ADMIN.

---

## 6. Endpoints

Every ID is a UUID except message IDs, which are integers. Times are ISO-8601 UTC. Amounts are
signed numbers in the account's currency (negative = debit).

### 6.1 Advisor workspace (customer file)

#### `findCustomerByReference` — find a customer by the reference they read out

`GET /api/v1/customers?externalRef=DEMO-00418` · **staff**

**200: `CustomerResponse`**

```json
{
  "id": "3fa85f64-...", "externalRef": "DEMO-00418", "firstName": "Amina", "lastName": "Haddad",
  "region": "Marseille", "segment": "AFFLUENT", "tenureMonths": 72,
  "accounts": [{
    "id": "…", "maskedIban": "FR76 **** **** 0189", "currency": "EUR", "balance": 1523.40,
    "overdraftLimit": 500.00, "status": "ACTIVE", "openedAt": "2020-03-02", "closedAt": null,
    "product": { "code": "CUR_PREMIUM", "name": "Compte courant Premium", "category": "CURRENT_ACCOUNT" },
    "cards": [{ "id": "…", "panLast4": "4242", "network": "VISA", "type": "DEBIT", "status": "ACTIVE", "expiresOn": "2028-06-30" }]
  }]
}
```

- **404:** unknown reference.
- IBANs are always masked.
- The churn risk is deliberately never sent.

#### `getCustomer` — the same record, by ID

`GET /api/v1/customers/{id}` · **staff** · 200 `CustomerResponse`; 404 when unknown.

#### `listCustomerTransactions` — recent movements across all accounts, newest first

`GET /api/v1/customers/{id}/transactions?count=10` · **staff** · `count` is 1–50, default 10.

**200**

```json
{ "customerId": "…", "transactions": [
  { "id": "…", "accountId": "…", "type": "CARD_PAYMENT", "amount": -89.90, "currency": "EUR",
    "label": "CB MARKET MARSEILLE", "counterparty": null, "status": "REJECTED", "bookedAt": "2026-10-02T08:58:00Z" } ] }
```

- `type` is one of: CARD_PAYMENT, ATM_WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, DIRECT_DEBIT, FEE,
  INTEREST, REFUND.
- `status` is one of: PENDING, BOOKED, REJECTED, DISPUTED.
- Any IBAN inside a label or counterparty is masked.

#### `listActiveServiceIncidents` — is there a known outage?

`GET /api/v1/service-incidents?region=Marseille` · **anyone signed in** · `region` is optional;
without it you get every active outage.

**200**

```json
{ "incidents": [
  { "id": "…", "service": "ONLINE_BANKING", "region": "Lyon", "severity": 3,
    "description": "Connexion a la banque en ligne indisponible", "startedAt": "…",
    "estimatedEnd": "…", "affectedCount": 1800 } ] }
```

- `region: null` means a national outage.
- `severity` runs from 1 (worst) to 5.
- `service` is one of: CARD_PAYMENTS, ONLINE_BANKING, MOBILE_APP, ATM_NETWORK, TRANSFERS.

#### `searchKnowledgeArticles` — procedures

`GET /api/v1/kb/articles?q=opposition&limit=5` · **staff** · `q` is 2–100 characters, `limit` 1–10
(default 5).

**200**

```json
{ "query": "opposition", "articles": [
  { "id": "…", "category": "CARDS", "title": "Faire opposition a votre carte", "content": "…",
    "tags": ["carte","opposition","fraude"], "version": 1, "updatedAt": "…" } ] }
```

#### `blockCard` — block a card

`POST /api/v1/cards/{id}/block` · **advisor, supervisor**

```json
{ "reason": "FRAUD_SUSPECTED" }
```

- `reason` is one of: LOST, STOLEN, FRAUD_SUSPECTED, CUSTOMER_REQUEST.
- **200: `CardResponse`:** `{ id, accountId, panLast4, network, type, status, expiresOn, blockedAt, blockReason }`.
- **Idempotent:** a card that is already blocked comes back unchanged, keeping its first reason.
- **409 `INVALID_STATE_TRANSITION`:** the card is EXPIRED or CANCELLED.
- A FRAUD_SUSPECTED block also sends a live alert to supervisors (§7).

#### `openTicket` — open a support ticket

`POST /api/v1/tickets` · **advisor, supervisor**

```json
{ "customerId": "…", "conversationId": "… (optional)", "category": "FRAUD",
  "title": "Paiements inconnus a l'etranger", "description": "optional, max 4000", "severity": 2 }
```

| Field | Rule |
|---|---|
| `category` | upper case and underscores, max 30 characters |
| `title` | max 200 characters |
| `severity` | 1–5, default 3 |

- **201: `TicketResponse`:** `{ id, customerId, conversationId, category, title, severity, status: "OPEN", createdAt }`.
- **400 `CONVERSATION_CUSTOMER_MISMATCH`:** the conversation isn't this customer's.
- A `status` in the body is ignored: a ticket always starts OPEN.

### 6.2 Conversation core (the life of a call)

**Statuses:**

```
QUEUED ──take──> ASSIGNED ──advisor's first message──> ACTIVE ──resolve──> RESOLVED
   │                │                                     │
   └──abandon───────┴──────────abandon────────────────────┼──> ABANDONED
                                                          └──escalate──> ESCALATED ──supervisor resolves──> RESOLVED
```

- **Any other move:** 409 `INVALID_STATE_TRANSITION`.
- **ESCALATED** is never abandoned and only a supervisor resolves it.

**`Conversation` object** (in every response below):

```json
{ "id": "…", "customerId": "…", "advisorId": "… or null while queued", "skill": "FRAUD",
  "intent": "FRAUD", "channel": "CHAT", "status": "ASSIGNED",
  "queuedAt": "2026-10-02T09:00:00Z", "assignedAt": "2026-10-02T09:00:42Z", "endedAt": null }
```

- **Never in a response:** the priority score, wait time, handle time or SLA flag. Those are
  internal; supervision gets them as aggregates (§6.3). For an "SLA timer" in the header, count from
  `queuedAt` on the client.
- **Skills:** `ACCOUNTS`, `CARDS`, `CREDIT`, `FRAUD`.
- **Intents:** `BALANCE`, `CARD`, `CREDIT`, `FRAUD`, `ACCOUNT_CLOSURE`, `OTHER`.

#### `openConversation` — register an incoming contact

`POST /api/v1/conversations` · **staff** (they register a contact on the customer's behalf)

```json
{ "customerId": "…", "skill": "FRAUD", "intent": "FRAUD" }
```

- `intent` is optional.
- **201:** a `Conversation` with status QUEUED, plus a `Location` header.
- **404:** unknown customer or skill.
- **400:** a malformed skill code.
- A CUSTOMER gets 403; this needs a schema change first.
- The priority (higher is served first) comes from churn risk, client segment and intent: fraud
  first, then account closure, card, credit.

#### `listQueues` — how long each queue is

`GET /api/v1/queues` · **staff**. An advisor sees only the skills they hold; a supervisor or admin
sees every queue.

**200**

```json
{ "queues": [ { "skill": "FRAUD", "waiting": 3, "oldestWaitSeconds": 42 },
              { "skill": "CARDS", "waiting": 0, "oldestWaitSeconds": null } ] }
```

#### `takeNextConversation` — the advisor takes the next call

`POST /api/v1/queues/{skill}/next` · **advisor** · no body.

| Answer | Meaning |
|---|---|
| **200: `Conversation`** | assigned to you, ASSIGNED |
| **204, no body** | nothing available right now: keep the button enabled and rely on the queue topic |
| **403** | you don't hold this skill |
| **409 `ADVISOR_UNAVAILABLE`** | you already hold your maximum number of calls |
| **404 `ADVISOR_PROFILE_NOT_FOUND`** | this login has no advisor profile (an admin setup problem) |

You always get the most urgent call (highest priority, then longest wait); the advisor never picks
one. Two advisors clicking at the same moment get two different calls.

#### `listMyConversations` — the advisor's personal queue

`GET /api/v1/conversations/mine` · **advisor** · **200:** `{ "conversations": [Conversation…] }`.
It lists ASSIGNED, ACTIVE and ESCALATED, oldest first.

#### `getConversation`

`GET /api/v1/conversations/{id}` · **its customer, its advisor, supervisor, admin**. 200: a
`Conversation`; 404 for anyone else.

#### `listMessages` — the transcript

`GET /api/v1/conversations/{id}/messages?limit=50` · **same readers** · `limit` is 1–200, default 50.
You get the latest `limit` messages, oldest first.

**200**

```json
{ "conversationId": "…", "messages": [
  { "id": 12, "conversationId": "…", "sender": "ADVISOR", "content": "Bonjour Madame Haddad", "sentAt": "…" },
  { "id": 13, "conversationId": "…", "sender": "CUSTOMER", "content": "Je n'ai pas fait ces paiements.", "sentAt": "…" } ] }
```

Load this once when you open the chat, then follow the conversation topic (§7) for new messages.

#### `postMessage` — write in the chat

`POST /api/v1/conversations/{id}/messages` · **its advisor or its customer**

```json
{ "content": "Je bloque votre carte tout de suite." }
```

- **201: `Message`.**
- **The server decides `sender`** from who you are; anything you put in the body is ignored.
- `content` is 1–2000 characters, otherwise 400.
- The advisor's first message moves ASSIGNED to ACTIVE.
- **409:** the conversation is closed.
- **403:** a supervisor (they read but don't write).
- **404:** anyone else.
- The advisor may keep writing after escalating, since the customer is still there.

#### `resolveConversation`

`POST /api/v1/conversations/{id}/resolve` · no body.

- The **assigned advisor** resolves an ACTIVE call.
- A **supervisor** resolves an ESCALATED call, which also closes the escalation.
- **200:** the `Conversation`, RESOLVED.
- **403:** the advisor tries to resolve an escalated call, or an admin tries.
- **409:** too early (still ASSIGNED: the advisor hasn't written yet).

#### `abandonConversation` — the customer left

`POST /api/v1/conversations/{id}/abandon` · **its customer, its advisor, supervisor, admin** · no
body. It works from QUEUED, ASSIGNED or ACTIVE: 200 `Conversation` ABANDONED. From ESCALATED or an
end state: 409.

#### `escalateConversation` — hand to a supervisor

`POST /api/v1/conversations/{id}/escalations` · **the conversation's assigned advisor only**

```json
{ "reason": "Fraude suspectee, besoin d'un superviseur" }
```

- **201:** created, and the conversation is now ESCALATED.
- **200:** an escalation was already pending and is returned (safe to retry).
- **Body:** `{ id, conversationId, reason, raisedBy: "ADVISOR", status: "PENDING", createdAt }`.
- **409:** the conversation isn't ACTIVE.
- **404:** another advisor's conversation.
- Supervisors get a live alert.

### 6.3 Supervision

#### `getLiveKpi` — today's banner

`GET /api/v1/supervision/kpi` · **supervisor, admin**

**200**

```json
{ "schemaVersion": 1, "at": "2026-10-02T09:15:00Z", "since": "2026-10-01T22:00:00Z",
  "queues": [ { "skill": "FRAUD", "waiting": 1, "oldestWaitSeconds": 12 } ],
  "waitingTotal": 3, "inService": 2, "resolvedToday": 14, "abandonedToday": 1,
  "averageWaitSeconds": 38.5, "slaRatio": 0.94, "abandonRate": 0.067 }
```

| Field | Meaning |
|---|---|
| `since` | Local midnight, Paris time |
| `averageWaitSeconds` | Mean wait of calls taken today |
| `slaRatio` | Answered on time ÷ measured. A customer who gave up after the target counts as a miss; one who gave up within it is not counted |
| `abandonRate` | abandoned ÷ (resolved + abandoned) |

The three ratios are **`null` when there is nothing to measure yet**; show "—", not 0%.

#### `listSupervisedConversations` — browse every live chat

`GET /api/v1/supervision/conversations` · **supervisor, admin**

| Parameter | Meaning |
|---|---|
| `status` | Repeatable: `?status=ACTIVE&status=ESCALATED` |
| `skill` | One of the four queues |
| `customerId` | One customer's conversations (the "all chats of this client" view) |
| `advisorId` | One advisor's conversations |
| `q` | Customer name or reference, case-insensitive |
| `from`, `to` | ISO-8601. `from` is queued at or after; `to` is queued before. Use `Z`, or URL-encode the `+` of an offset |
| `page`, `size` | Size 1–100, default 20 |

**200:**

```json
{ "content": [ {
    "id": "…", "status": "ESCALATED", "skill": "FRAUD", "intent": "FRAUD", "channel": "CHAT",
    "queuedAt": "…", "assignedAt": "…", "endedAt": null,
    "customer": { "id": "…", "name": "Amina Haddad", "reference": "DEMO-00418" },
    "advisor":  { "id": "…", "name": "Karim Benali", "reference": null },
    "waitSeconds": 42, "handleSeconds": null, "slaMet": true,
    "messageCount": 12, "lastMessageAt": "…", "pendingEscalation": true } ],
  "page": { "number": 0, "size": 20, "totalElements": 57, "totalPages": 3 } }
```

- Newest first. `advisor` is `null` while the conversation waits in its queue.
- `skill` is matched case-insensitively. An unknown skill returns an empty page, not an error.
- On a busy floor, `totalElements` can differ by one from what the page shows, because a conversation
  can arrive between the two reads. Refresh, or follow the queue topics.
- To open a row, use `getConversation` and `listMessages`, then subscribe to `/topic/conversation/{id}`.
  A supervisor may read every live conversation.
- **The supervision screen:** a filterable table with escalated chats on top (`status=ESCALATED`) and a
  click opening the transcript. On the customer view, `customerId=` shows all of that client's chats.

### 6.4 Account administration (ADMIN only)

**`User` object:** `{ "id", "email", "firstName", "lastName", "role", "active", "createdAt" }`. There
is never a password or a hash.

#### `listUsers`

`GET /api/v1/admin/users?role=ADVISOR&active=true&q=karim&page=0&size=20`

- Every filter is optional. `q` matches email, first name and last name.
- `size` is 1–100 (default 20).
- **200:** `{ "content": [User…], "page": { "number": 0, "size": 20, "totalElements": 42, "totalPages": 3 } }`.
  Newest first.

#### `getUser`

`GET /api/v1/admin/users/{id}`: **200** `User`, or 404.

#### `createUser` — give someone access

`POST /api/v1/admin/users`

```json
{ "email": "karim.benali@bank.fr", "firstName": "Karim", "lastName": "Benali", "role": "ADVISOR",
  "password": "temporary password, 12 chars minimum" }
```

- **201:** `User`, plus a `Location` header. The email is stored in lower case.
- **400:** a weak password (under 12 characters, over 72 bytes, or containing the email's name), a bad
  email or a missing field.
- **409 `EMAIL_ALREADY_USED`:** the address is taken.

#### `changeUserRole`

`PUT /api/v1/admin/users/{id}/role` with `{ "role": "SUPERVISOR" }`. **200:** `User`.

- The user's current token stops working at once; their next login carries the new role.
- **409 `SELF_LOCKOUT`:** you tried to change your own role.

#### `blockUser` / `unblockUser`

`POST /api/v1/admin/users/{id}/block` and `POST /api/v1/admin/users/{id}/unblock`, no body. **200:** `User`.

- A blocked user can't log in. Their current token gets **401** on the next request, and their open
  live connection stops receiving.
- Blocking yourself: **409 `SELF_LOCKOUT`**.
- Repeating a block or unblock returns 200 with nothing changed.

### 6.5 Platform

#### `getHealthStatus`

`GET /api/v1/health/status` · no token. **200:**
`{ "status": "UP|DEGRADED|DOWN", "service": "callverse-backend", "version": "…", "profile": "dev", "timestamp": "…" }`.
**DEGRADED** means the AI service is not configured; human advisors still work.

---

## 7. Live channel (STOMP)

### Connect

STOMP 1.2 over a native WebSocket at `ws://localhost:8080/ws`. With `@stomp/stompjs`:

```ts
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: 'ws://localhost:8080/ws',
  connectHeaders: { Authorization: `Bearer ${token}` },   // STOMP header, not an HTTP header
  heartbeatIncoming: 10000,
  heartbeatOutgoing: 10000,
  reconnectDelay: 3000,
  beforeConnect: () => { client.connectHeaders = { Authorization: `Bearer ${getFreshToken()}` }; },
});
client.onConnect = () => {
  client.subscribe(`/topic/conversation/${conversationId}`, (frame) => onEvent(JSON.parse(frame.body)));
};
client.onStompError = (frame) => console.warn('refused:', frame.headers['message']); // "Access denied"
client.activate();
```

### Rules

- **Listen only.** Never `publish` or `SEND`: the server refuses it and closes the connection. Every
  action goes through REST.
- **Exact topic names only**, with no `*` wildcards. A refused subscription returns an ERROR frame
  "Access denied" and closes the connection.
- **When the token expires**, the socket stops receiving and new subscriptions are refused.
  Reconnect with a fresh token: `beforeConnect` above does that.
- **Order:** frames can arrive slightly out of order. Keep the latest `occurredAt` per conversation
  and ignore older status frames. Messages carry `messageId`; skip any you already have.

### Topics

| Topic | Who may listen | What arrives |
|---|---|---|
| `/topic/conversation/{id}` | its customer, its advisor, supervisor, admin | messages and status changes |
| `/topic/queue/{skill}` | an advisor holding the skill, supervisor, admin | arrivals and departures |
| `/topic/supervision/kpi` | supervisor, admin | the full banner (same shape as `getLiveKpi`) after each change |
| `/topic/supervision/alerts` | supervisor, admin | escalations and fraud card blocks |
| `/topic/runs/{runId}` | supervisor, admin | nothing yet (simulation phase) |

### Payloads (all carry `schemaVersion: 1`)

**Conversation topic, `MESSAGE_POSTED`:**

```json
{ "schemaVersion": 1, "type": "MESSAGE_POSTED", "occurredAt": "…", "conversationId": "…", "status": "ACTIVE",
  "messageId": 13, "sender": "CUSTOMER", "content": "Je n'ai pas fait ces paiements.", "sentAt": "…" }
```

**Conversation topic, `STATUS_CHANGED`:** the message fields are `null`.

```json
{ "schemaVersion": 1, "type": "STATUS_CHANGED", "occurredAt": "…", "conversationId": "…", "status": "ESCALATED",
  "messageId": null, "sender": null, "content": null, "sentAt": null }
```

**Queue topic:** `type` is `CONVERSATION_QUEUED` or `CONVERSATION_LEFT_QUEUE`. On departure,
`status` says why: ASSIGNED or ABANDONED.

```json
{ "schemaVersion": 1, "type": "CONVERSATION_QUEUED", "occurredAt": "…", "skill": "FRAUD",
  "conversationId": "…", "status": "QUEUED", "waiting": 3 }
```

**Alerts topic:** fields that don't apply to the alert are null.

```json
{ "schemaVersion": 1, "type": "ESCALATION_RAISED", "occurredAt": "…", "customerId": "…",
  "conversationId": "…", "escalationId": "…", "cardId": null, "cardLast4": null }
```

- `type` is `ESCALATION_RAISED` or `CARD_BLOCKED_FRAUD`.
- An alert carries IDs only; fetch the customer through REST if the screen needs a name.

---

## 8. TypeScript types for the live payloads

The REST types come from `openapi-typescript`; the live payloads are not in OpenAPI, so here they are:

```ts
export type ConversationStatus = 'QUEUED' | 'ASSIGNED' | 'ACTIVE' | 'ESCALATED' | 'RESOLVED' | 'ABANDONED';

export interface ConversationEvent {
  schemaVersion: 1;
  type: 'MESSAGE_POSTED' | 'STATUS_CHANGED';
  occurredAt: string;
  conversationId: string;
  status: ConversationStatus;
  messageId: number | null;
  sender: 'CUSTOMER' | 'ADVISOR' | 'SYSTEM' | null;
  content: string | null;
  sentAt: string | null;
}

export interface QueueEvent {
  schemaVersion: 1;
  type: 'CONVERSATION_QUEUED' | 'CONVERSATION_LEFT_QUEUE';
  occurredAt: string;
  skill: string;
  conversationId: string;
  status: ConversationStatus;
  waiting: number;
}

export interface SupervisionAlert {
  schemaVersion: 1;
  type: 'ESCALATION_RAISED' | 'CARD_BLOCKED_FRAUD';
  occurredAt: string;
  customerId: string;
  conversationId: string | null;
  escalationId: string | null;
  cardId: string | null;
  cardLast4: string | null;
}

// /topic/supervision/kpi carries the same shape as the REST LiveKpi schema.
```

---

## 9. Build order — screen by screen

| # | Screen | Use | Ready |
|---|---|---|---|
| 1 | **Login + role routing** | `login`, `getCurrentUser` | ✅ |
| 2 | **Advisor workstation** (the demo screen) | header: `listMyConversations`, `listQueues` plus the queue topics; **take next**: `takeNextConversation`; chat: `listMessages`, `postMessage` plus the conversation topic; customer panel: `getCustomer` (from `customerId`), `listCustomerTransactions`; actions: `blockCard`, `openTicket`, `escalateConversation`, `resolveConversation`, `abandonConversation`; side: `searchKnowledgeArticles`, `listActiveServiceIncidents` | ✅ |
| 3 | **Live supervision** | `getLiveKpi`, then the KPI topic; alerts topic; per-skill queues from the banner; **browse every chat with `listSupervisedConversations`** (by client, advisor, status), open one with `getConversation` / `listMessages` + its topic; resolve escalated calls | ✅ (advisor presence list: mock) |
| 4 | **Front desk / switchboard** (register a contact) | `findCustomerByReference` → `openConversation` | ✅ |
| 5 | **Customer chat** | logged-in customer: the conversation topic, `listMessages`, `postMessage`, `abandonConversation` on a conversation staff opened for them | ⚠️ partial: they can't start a chat or list their own conversations yet |
| 6 | Simulation studio | — | ❌ mock |
| 7 | Quality review | — | ❌ next backend phase |
| 8 | Back office | — | ❌ mock |

**The 2-minute demo path:**

1. Sarah opens the supervision screen; Karim opens his workstation.
2. The front desk registers Amina on FRAUD, and it appears in Karim's queue live.
3. Karim clicks *take next*.
4. They chat, live on both screens.
5. Karim blocks card 4242 for FRAUD_SUSPECTED; Sarah gets an alert.
6. Karim escalates; Sarah resolves. Her banner updates throughout.

---

## 10. Not ready yet — mock these

| Missing | Why | Mock with |
|---|---|---|
| A customer listing their own conversations and accounts, or opening a chat | needs a uniqueness rule on the customer login (schema owner) | fixed data for the demo customer |
| Advisor presence (available / break / offline) and the advisor list | next phase | a static list |
| Supervisor reassigning a call | next phase | disabled button |
| "Waited too long" SLA alerts | needs a background job | nothing |
| Simulation runs, experiment KPIs, run comparison, `/topic/runs/{runId}` | AI phase, later | JSON fixtures |
| Quality scores | FS-4, next | JSON fixtures |
| Commercial credit (goodwill gesture) | decisions pending | disabled button with the ceiling shown |
| AI reply suggestion with sources | AI phase | static suggestion |
| Pagination | no list is paginated yet; lists are bounded instead | — |

---

## 11. Gotchas

1. **404 can mean "not yours".** For a stranger's conversation the server answers exactly as if it
   didn't exist. Show "not found", not "forbidden".
2. **403 must not trigger a re-login.** Only 401 means the token is gone.
3. **204 from take-next is normal.** Keep the button enabled; the queue topic tells you when a call
   arrives.
4. **The KPI ratios can be `null`.** Display "—".
5. **Never send `sender`, `status`, `priority`** or other server-decided fields: they are ignored, but
   it confuses reviewers.
6. **Amounts are numbers** (`1523.4`), not strings. Format them with `Intl.NumberFormat('fr-FR', { style: 'currency', currency })`.
7. **Message IDs are numbers; every other ID is a UUID string.**
8. **One STOMP connection per tab**, many subscriptions on it. Unsubscribe when the chat closes.
9. **Swagger's "Authorize" button** takes the token without the word `Bearer`.
10. **A 401 can arrive in the middle of a session.** If an admin blocks the user or changes their role,
    the very next call is 401: send them to login, where a blocked account gets `INVALID_CREDENTIALS`.

---

## 12. Changes and contact

- Every backend change to a route or field changes `openapi.yaml` in the same commit. I'll post in
  the team channel: *what changed, which operationId, breaking or not*. Then regenerate your types.
- Live payload changes bump `schemaVersion`.
- Need a field, a route or a CORS origin? Ask me with the screen and the exact data you need; I'll
  answer with the contract first, the code after.

### Changelog

| Date | Change |
|---|---|
| 2026-10-01 | Advisor workspace (9 operations), secured live channel, supervision alerts |
| 2026-10-02 | Conversation core (10 operations): queues, take next, chat, resolve, abandon; escalation now moves the conversation to ESCALATED and only its own advisor may escalate; live queue, conversation and KPI topics |
| 2026-10-03 | Account administration (6 operations): create users, change roles, block and unblock, effective at once (REST and live channel) |
| 2026-10-10 | Supervisors browse every live conversation (`listSupervisedConversations`): filters by client, advisor, status, skill, text and date, with operating figures per row |
