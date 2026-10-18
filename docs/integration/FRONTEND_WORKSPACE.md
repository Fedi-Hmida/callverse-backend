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
