# Advisor safety and recovery

The backend remains the authority for escalation and banking operations. In autonomous mode,
an explicit personal fraud report bypasses the provider and requests a real escalation after
the guarded reply write. A returned `FRAUD` intent also overrides `NONE`. The deterministic
French/English phrase rules are conservative safeguards, not a complete fraud classifier.
Human takeover, stale-trigger checks and conversation state checks still apply.

Provider errors, timeouts and invalid contracts produce an honest unavailability message and
request a persisted human escalation. They do not imply that an earlier banking tool was rolled
back. Existing banking idempotency and authorization checks continue to apply. No card-blocking
tool exists; known generated claims that the agent blocked a card are replaced by a handover.
Technical UUIDs are masked in customer replies while authorized tool traces retain their IDs.

Recognized procedure questions use current published knowledge excerpts directly, without
inventing a customer interface or execution result. Missing or low-relevance documents cause
handover. This is an extractive path, not a guarantee about every free-form model answer.

The published semantic index refreshes in the background every `KB_INDEX_REFRESH_SECONDS`
(default 60; zero disables it). Unchanged chunks are skipped. Rebuilds recheck publication and
content under the article lock. Published text search remains available during embedding
failures. Startup health is not an assertion that every embedding is ready.

Advisor/Simulator generations sharing a model are serialized within each worker and respect
provider cooldowns, bounded retries and the outer request deadline. No banking tool is retried
by the provider retry layer. This reduces bursts; it does not increase provider quotas or
coordinate independent worker processes. Production quota sizing still requires load testing.

The quality judge selects citation IDs from short exact transcript excerpts; the service expands
those IDs locally, rejecting unknown IDs and customer quotes used as advisor claims. A shared
proof schema reduces request size. A malformed evaluation can be regenerated once from the original transcript,
within the configured time budget. Missing proofs and out-of-range grades are rejected rather
than manufactured or clamped to a perfect grade. Citations are validated locally. Semantic
grading remains probabilistic and requires calibration against human reviewers.

Migration compatibility: V9 is reserved for the advisor explanation lookup index. Customer
conversation soft deletion is introduced by V10. Keep the two repositories on their compatible
integration branches and apply forward migrations without rewriting Flyway history.
