# Workforce Manager — backend proposal flow

## Purpose and boundary

The Workforce Manager is an advisory component. Spring asks the Python service for one
recommendation when an authorized supervisor requests it, returns the proposal to the
supervision client, and records the observation and proposal in `agent_decision`.
No advisor is reassigned by this flow. The existing schema's `approved_by` remains null;
this integration adds no approval or apply route and requires no Flyway change.

The endpoint is `POST /api/v1/supervision/workforce/recommendations`, restricted to
supervisors and administrators. Its successful response contains the proposal, the
observation used, the trace id, and any applicable data caveats. If the Python service
is unavailable, times out, or returns an invalid proposal, Spring responds with
`503 WORKFORCE_UNAVAILABLE`; it does not persist a success-shaped fallback.

## Data sent to Python

Spring sends `POST /ai/workforce/decide?strategy=PPO` with the existing `X-AI-Key`
configuration. The Python `/ai/*` authentication uses `AI_SERVICE_KEY` when configured.
The request contains only these live aggregates:

| Field | Backend source / interpretation |
|---|---|
| `queues` | Current live `QUEUED` conversation count per canonical skill. |
| `avg_wait` | Mean elapsed seconds of conversations currently waiting in each queue, not a historical service average. |
| `available` | Distinct non-simulated advisors with that skill and at least one free `max_concurrent` slot. |
| `sla_today` | Existing live KPI SLA ratio for the operations day. If no observations exist, Spring passes the training-compatible neutral value `1.0` and sets `slaMeasured=false` in its trace/response. |
| `hour` | Current hour in `callverse.operations.zone` (default `Europe/Paris`). |
| `trend` | Neutral value `1.0`; a live trend metric does not yet exist. |

The four model skills now exactly match Spring's `SkillCode`: `ACCOUNTS`, `CARDS`,
`CREDIT`, and `FRAUD`. A multi-skilled advisor is counted in each eligible skill, so
the availability pools may overlap. This is surfaced in the response and means the
proposal is not an executable staffing plan. A supervisor must interpret it before any
out-of-band staffing change.

## Model and measured limitations

The selected artifact is `ai-service/artifacts/workforce_banking_v1/best/best_model.zip`.
It was trained with PPO for 100,000 steps (seed 7); the selected checkpoint was the
best medium-load `EvalCallback` checkpoint at 50,000 steps. The default
`WORKFORCE_MODEL_PATH` points to this artifact.

The selected model was evaluated for five synthetic 480-minute episodes per load
(seeds 42–46). These are simulation results, not live-bank performance:

| Load | PPO SLA | PPO abandon | PPO mean wait | Baseline SLA range | Baseline abandon range |
|---|---:|---:|---:|---:|---:|
| Low | 0.755 | 0.054 | 104.8 s | 0.718 | 0.000–0.005 |
| Medium | 0.513 | 0.176 | 211.1 s | 0.305–0.357 | 0.097–0.127 |
| Saturated | 0.067 | 0.567 | 590.0 s | 0.025–0.045 | 0.563–0.567 |

PPO improves measured SLA over the two baselines at each simulated load, but its
abandon rate is worse at low and medium loads; at saturated load it matches
`STATIC_FIFO` and is 0.004 above `THRESHOLD`. This is why the integration is
limited to supervised proposals; it does not automate reassignment or claim the
model is production-validated.

## Validation and remaining agents

The Workforce Python contract tests, HTTP adapter tests, Spring use-case tests,
PostgreSQL Testcontainers flow, access-control checks, architecture rules, and OpenAPI
contract are validated separately. The current full-build/full-suite result is recorded
in `PROGRESS.md`.

The Quality Analyst and Client Simulator are not connected to live backend workflows:
the Quality Analyst needs a defined post-conversation evaluation trigger and data
policy, while the Simulator remains an experiment tool. Neither is invoked by this
Workforce endpoint.
