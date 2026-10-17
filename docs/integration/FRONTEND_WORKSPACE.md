# Frontend AI workspace

Branch `integration/ai-frontend` extends Douaa's `integration/backend-fedi` at
`b54dde3` without changing that branch or `main`. The matching frontend is
`integration/ai-connected` in `bellaaghaaziz/callverse-frontend`.

Five operations expose the data needed by the supervisor/admin interface:

- `GET /api/v1/simulation-runs/options`: existing scenario and strategy references.
- `POST /api/v1/simulation-runs/scenarios`: a named minimal single-dialogue scenario,
  validated as non-blank and at most 120 characters. This does not configure load campaigns.
- `GET /api/v1/simulation-runs`: the twenty most recent runs, newest first.
- `GET /api/v1/simulation-runs/{runId}/messages`: the isolated dialogue, not private
  sources or tool calls. The run is checked before reading its conversation.
- `GET /api/v1/supervision/conversations/{conversationId}/quality`: persisted live
  evaluations (score, criteria, explanation, evaluator, date), without agent traces.
  Missing evaluation is an empty list; unknown or simulated conversation is 404.

All operations require SUPERVISOR/ADMIN. The production filter chain explicitly
admits authenticated requests and method authorization checks the role. Both roles
can discover reference options; customers and advisors cannot use these operations.
No migration, live queue modification, new AI write tool, assignment application,
or re-evaluation trigger is introduced. Sources and tool traces remain private.

The existing Workforce recommendation route is called on demand. The existing
asynchronous Simulator/Advisor routes provide run creation/status. Quality remains
the existing post-resolution live evaluation; the frontend adds reading only.
The simulation strategy is metadata in this conversational runner, not a comparison
of staffing policies. No simulated transcript is exposed through live routes/topics.

`AiWorkspaceIntegrationTest` validates production access controls, scenario creation,
catalog reads, transcript isolation and quality absence/persistence. OpenAPI and its
operation-id test include the five new operations. The frontend types were generated
from this contract. All thirteen workspace/contract/architecture tests pass.

The pgvector adapter imports the embedding library lazily so the API and Workforce
can start without initializing document embedding dependencies. RAG itself still
requires the library, configured vector database and indexed documents.

For local startup and UI verification, see `docs/AI_CONNECTED.md` in the frontend.
An actual Spring-to-Python PPO request succeeded; browser workflow tests intercept
mutations. Full real-LLM dialogues and quality scores require configured model services.
