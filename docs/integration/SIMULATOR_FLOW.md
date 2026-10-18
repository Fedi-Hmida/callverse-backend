# Client Simulator flow

## Purpose and isolation

The Simulator is a synthetic customer, not a live-conversation agent. A run creates
one `customer` with `is_simulated=true`, one conversation linked by
`conversation.run_id`, and one `simulation_run`. It does not open a live queue item,
publish a live conversation event, or invoke live-conversation handlers. Existing
live conversation reads, queue selection, supervision queries and operations exclude
rows with a non-null `run_id`.

Only supervisors and administrators can manage runs:

- `POST /api/v1/simulation-runs` starts a run and returns `202 Accepted` with a
  `Location` header.
- `GET /api/v1/simulation-runs/{runId}` returns status, terminal conversation
  status, message count and a safe failure description.

The request supplies the scenario, strategy, random seed, Simulator profile,
objective, initial patience and satisfaction, maximum turn count, customer segment
and tenure. The maximum is 50 customer turns. The existing unique constraint on
`(scenario_id, strategy_id, seed)` deliberately prevents re-running that triple.

## Sequence

1. Spring validates the explicit setup, creates the run, and writes a synthetic
   customer and an `ACTIVE` conversation linked to that run.
2. A bounded executor schedules the orchestration, so the HTTP request does not
   wait for the LLM. The queue is bounded; a rejected task is marked `FAILED`.
3. Spring calls `POST /ai/simulator/next-message` with the last 20 customer/advisor
   messages, the previous Advisor reply and the current synthetic customer state.
4. If the Simulator returns `en_cours`, Spring persists its message as a
   `CUSTOMER` turn, then calls `/ai/advisor/respond` with mode `SIMULATION`.
5. Spring persists the Advisor reply as an `ADVISOR` turn with `ai_generated=true`,
   then repeats until the Simulator resolves or abandons the conversation, or the
   turn limit is reached.
6. The run and its conversation are marked terminal. On agent failure, timeout,
   invalid response or turn-limit exhaustion, the run is marked `FAILED` with a
   generic message; no secret or remote response body is persisted.

The Advisor history contains at most the 20 most recent customer/advisor messages
before the current customer turn. `SYSTEM` messages are not sent to either agent.
The Advisor's simulation mode permits document search but refuses backend business
tools, so simulation cannot inspect or mutate live customer data.

## Persistence and observability

This flow uses existing schema only; no migration is required. Advisor `sources`
and `tool_calls` are stored in the existing message columns, and both agents'
observations/actions are stored in `agent_decision` under the simulation run.
Those traces are not included in public message DTOs or live real-time events; the
applicability of rule A9 to this flow remains unconfirmed.

The AI key is configured through `AI_SERVICE_KEY` and sent as `X-AI-Key`. The key
must not be written to logs or run error messages.

## Verification and limitations

The Java persistence and controller integration tests use Testcontainers PostgreSQL
and mock the AI HTTP/agent boundaries. The focused HTTP adapter tests use
`MockRestServiceServer`; the Python Advisor graph tests verify the simulation-mode
tool restriction. These checks do not constitute a live LLM end-to-end run.

The context is intentionally reduced to known customer attributes and recent
transcript only. There is no live conversation history, open-ticket lookup, or
business action in simulation mode. The configured run uniqueness also means a
scenario/strategy/seed tuple can be run only once under the current schema.
