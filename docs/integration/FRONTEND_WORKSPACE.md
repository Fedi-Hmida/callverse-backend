# Integrated frontend workspace — 2026-10-18

This branch integrates Fedi's `main` at `bcc172c` while retaining the frontend workspace
extensions. `docs/frontend/AI_INTEGRATION_GUIDE.md` describes upstream; its “no quality
read route yet” and “no dialogue scenario creation” statements do not apply to these extensions.

## Retained extensions

- GET `/api/v1/customers/directory`: paginated minimal real-customer directory, staff only.
- GET `/api/v1/conversations/accessible`: role/ownership-scoped searchable conversations.
- GET `/api/v1/simulation-runs/options`: scenario and strategy catalog for dialogues.
- POST `/api/v1/simulation-runs/scenarios`: named minimal single-dialogue scenarios.
- GET `/api/v1/simulation-runs`: twenty latest dialogue runs; twin runs are filtered before pagination.
- GET `/api/v1/simulation-runs/{runId}/messages`: isolated transcript without private traces.
- GET `/api/v1/supervision/conversations/{id}/quality`: stored live evaluations.
- POST `/api/v1/supervision/conversations/{id}/quality`: queue an eligible unresolved-grade
  analysis asynchronously (202); an already evaluated conversation is not overwritten.

The six simulation/quality routes require SUPERVISOR/ADMIN; customer directory requires
staff. Production filter-chain rules and method authorization both remain enabled.
Conversation selection uses the signed-in role's ownership policy. No raw IDs are required in forms.

## Upstream functionality kept

Human takeover and OFF/AUTONOMOUS modes; real AI escalation; trusted tool identifiers;
personal-data masking; fail-closed service authentication; ordered live events; strict quality
scores/evidence; cited-document and procedure grounding; scheduled quality retries;
digital-twin scenarios, runs and comparison with retryable failed calculations.

Quality HTTP read timeout remains configurable, default 130 seconds (above Python's default 120).
Manual quality requests and automatic retry use the same guarded dispatcher.
Lazy embedding imports and safe quote-typography/unique in-range index alignment are retained.
Invented/ambiguous quotes, customer-only claims and invalid indices remain rejected.

## Contract and verification

OpenAPI includes upstream plus the eight directory/workspace operations. The published
operation-id test and generated frontend types are updated together.
544 backend tests pass, including production access, paging, twin/dialogue isolation,
quality requests and ArchUnit. 143 Python tests pass (one real-LLM test excluded by default).
Frontend tests/build and real local PPO twin comparisons are documented in the frontend's
`docs/AI_CONNECTED.md`. No Groq key or secret is committed; real autonomous LLM dialogue
still depends on environment configuration.

## Customer conversation rules — 2026-10-18

Three customer-side changes. The backend is done and tested; the frontend steps are listed per rule.

### One live conversation per customer

`POST /api/v1/me/conversations` and the front desk's `POST /api/v1/conversations` refuse a second
live conversation (queued, assigned, active or escalated) for the same customer:
`409 CONVERSATION_ALREADY_OPEN`, the message naming the conversation to resume. The rule is checked
under a lock on the customer row, so two clicks at the same instant still open one.

Why: the AI Advisor answers only assigned or active conversations. A second conversation waited in
a queue the AI does not answer, while the first got replies — the "AI answers only one chat" bug.

Frontend: on this 409, show « Vous avez déjà une conversation en cours. Terminez-la ou supprimez-la
pour en ouvrir une nouvelle. » with a button that opens the existing conversation.

### Removing a conversation from the customer's space

`DELETE /api/v1/me/conversations/{id}` (`deleteOwnConversation`, customer only, 204).

- A live conversation is ended as ABANDONED first; a closed one keeps its status.
- An escalated conversation is refused: `409 CONVERSATION_UNDER_SUPERVISION`.
- Not the caller's, simulated, or already removed: 404.
- The bank keeps the record (V9 adds `conversation.customer_deleted_at`). The customer no longer
  reads it (404), lists it or subscribes to it; staff still do.

What the advisor and the supervisor receive, live:
1. a SYSTEM message on `/topic/conversation/{id}`: « Le client a supprimé cette conversation de son
   espace. » — also in the transcript after a reload;
2. `STATUS_CHANGED` → `ABANDONED` when it was live (queued: also `CONVERSATION_LEFT_QUEUE`);
3. `CONVERSATION_DELETED_BY_CUSTOMER` on `/topic/queue/{skill}`, which every advisor of that skill
   already listens to, whichever conversation is on screen.

Frontend: a « Supprimer » action with a confirmation in the customer's list; in the advisor
workspace, on `CONVERSATION_DELETED_BY_CUSTOMER` for a conversation the advisor holds, a toast
« Le client a supprimé la conversation » and a refresh of the held list.

### List headings

`GET /api/v1/conversations/accessible` items gain three fields:

| Field | Customer caller | Staff caller |
|---|---|---|
| `counterpartName` | the advisor's name; `null` while waiting for one | the customer's name |
| `subject` | the request in French (intent, else the skill's label) | same |
| `removedByCustomer` | always false (removed rows are not listed) | true when the customer removed it |

Frontend (`ConversationPicker`): head each row with `counterpartName ?? "En attente d'un conseiller"`
and `subject`, instead of `customerName`. Staff lists can show a « Supprimée par le client » badge.
