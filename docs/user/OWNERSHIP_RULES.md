# CallVerse — Ownership-Rule Catalogue

| | |
|---|---|
| **Date** | 2026-09-15 · **Commit** `225af017` |
| **Purpose** | Every fine-grained authorization rule the system needs, catalogued **before any is implemented**, with the actual columns that decide it. |
| **Totals** | 15 CUSTOMER · 14 ADVISOR · 7 SUPERVISOR · 4 ADMIN · 7 `/internal` business conditions · 11 mode-scoping rules · **22 ownership-blind repository methods** · **13 rules the schema cannot express** |

**The distinction this document maintains throughout:**

| | |
|---|---|
| **Coarse role gate** | *"only a SUPERVISOR may call this"* — expressible as `@PreAuthorize` |
| **Fine-grained ownership rule** | *"a CUSTOMER may read only THEIR OWN invoice"* — **not** expressible in an annotation; lives in the use-case method body |

These are separate work items. `CALLVERSE_PROJECT_CONTEXT.md:449-450` warns: *"Miss them and a customer reads another customer's invoices by changing a UUID in the URL."*

`P` below = the authenticated principal's `app_user.id`.

---

## 0. The precondition that blocks every rule here

**Every predicate in sections A and B begins with the same hop, and that hop has no query behind it.**

- `customer.user_id` → `app_user(id)` — `V1:58`, `Customer.java:49-51`
- `advisor.user_id` → `app_user(id)` — `V1:130`, `Advisor.java:51-53`
- **`CustomerRepository` has no `findByUserId`** (`CustomerRepository.java:15-27` — `findByExternalRef`, `findByZone`, `findBySimulatedFalse` only)
- **`AdvisorRepository` has no `findByUserId`** (`AdvisorRepository.java:17-50`)
- A grep for `findByUser|UserId` across the repositories package returns **zero results**

Two methods must exist before any other rule can be written:

```java
CustomerRepository.findByUserId(UUID userId)
AdvisorRepository.findByUserId(UUID userId)
```

> ⚠️ **Their return type is not obvious, and that is itself a finding.** Neither `user_id` column carries a `UNIQUE` constraint (verified: 0 matches). Two `customer` rows may legally point at one `app_user`. **Until a unique constraint exists, `findByUserId` must return a `List`, and "the customer whose `user_id` is mine" is not well-defined.** See S-1.

---

## A. CUSTOMER-owned resources

| # | Resource | Coarse gate | Ownership predicate — actual columns | Hops | Use case | Repository affected |
|---|---|---|---|---|---|---|
| A1 | Own profile | `hasRole('CUSTOMER')` | `customer.user_id = P`. **Never trust a `{customerId}` path variable** — resolve from `P` and ignore the path | 1 | `GetOwnCustomerProfileQuery` | **ADD** `findByUserId`. `findById` must be unreachable here |
| A2 | Own profile — **write** | `hasRole('CUSTOMER')` | `customer.user_id = P` **plus a field allow-list**: only `phone`, `first_name`, `last_name`. **`churn_risk` (`V1:65-66`) feeds `conversation.priority_score` — a writable `churn_risk` is a self-service queue-jump.** `external_ref`, `is_simulated`, `user_id`, `zone`, `tenure_months` also read-only | 1 | `UpdateOwnCustomerProfileCommand` | `save` must never receive a client-supplied entity. ⚠️ allow-list membership is a policy call |
| A3 | Contract | `hasRole('CUSTOMER')` | `contract.customer_id = (select id from customer where user_id = P)`. FK `V1:90` | 2 | `GetOwnContractsQuery` | No contract repository exists — reached via `Customer.getContracts()` (`Customer.java:92`). Safe **iff** the `Customer` came from A1, not `findById` |
| **A4** | **Invoice — the two-hop case** | `hasRole('CUSTOMER')` | `app_user.id` = `customer.user_id`, `customer.id` = `contract.customer_id`, `contract.id` = `invoice.contract_id`. FKs `V1:58`, `:90`, `:103`. **`invoice` has no `customer_id` column — the join through `contract` is mandatory** | **3** | `GetOwnInvoicesQuery` | `InvoiceRepository.findByContractIdOrderByPeriodStartDesc` (`:22`) — **ownership-blind** |
| A5 | Ticket | `hasRole('CUSTOMER')` | `ticket.customer_id = (select id from customer where user_id = P)`. FK `V1:209` | 2 | `GetOwnTicketsQuery`, `CreateOwnTicketCommand` | `TicketRepository:18` — **ownership-blind**. On create, `customer_id` **derived from `P`, never from the body** |
| A6 | Conversation | `hasRole('CUSTOMER')` | `conversation.customer_id = (select id from customer where user_id = P)`. FK `V1:166` | 2 | `GetOwnConversationsQuery` | `ConversationRepository:57` — **ownership-blind** |
| A7 | Conversation — **field-level** | `hasRole('CUSTOMER')` | Owning the row does not confer every column. `priority_score` (`V1:175`), `sla_met` (`:181`), `handle_seconds`, `wait_seconds` are internal operating data — **project a DTO subset, do not serialize the entity** | 2 | same as A6 | mapper rule |
| A8 | Message — read | `hasRole('CUSTOMER')` | `app_user.id` = `customer.user_id`, `customer.id` = `conversation.customer_id`, `conversation.id` = `message.conversation_id` | **3** | `GetOwnConversationTranscriptQuery` | `MessageRepository` `:21`, `:24`, `:26`, `:32` — **all four ownership-blind** |
| A9 | Message — **field-level** | `hasRole('CUSTOMER')` | `message.sources` and `tool_calls` (`V1:200-201`) are the RAG/XAI trace — **strip from the customer projection**; they disclose internal KB article ids and which business tools fired | 3 | same as A8 | mapper rule |
| A10 | Message — write | `hasRole('CUSTOMER')` | A8 **plus** `sender` forced to `'CUSTOMER'` (CHECK `V1:196-197`) and `ai_generated` forced `false` — **never read from the body**. **Plus a state predicate**: `conversation.status ∈ (QUEUED, ASSIGNED, ACTIVE, ESCALATED)`; posting into `RESOLVED`/`ABANDONED` → `INVALID_STATE_TRANSITION` | 3 + state | `PostOwnMessageCommand` | entity constructed server-side from `P` |
| A11 | Commercial credit — read | `hasRole('CUSTOMER')` | `commercial_credit.customer_id = (...)`. FK `V1:225`. **Read only** — a customer may never create one | 2 | `GetOwnCreditsQuery` | `CommercialCreditRepository:39` — **ownership-blind** |
| A12 | Credit — **field-level** | `hasRole('CUSTOMER')` | `granted_by` / `approved_by` (`V1:229-230`) identify staff. Expose amount, reason, date — not who granted or signed off | 2 | same as A11 | mapper rule ⚠️ policy |
| **A13** | **Quality evaluation — ANTI-RULE** | **no CUSTOMER access at all** | A customer owns the conversation, and `quality_evaluation.conversation_id` hangs off it (`V1:445`). **Ownership must not be transitive.** This is the internal grading of the advisor who served them — `global_score`, `scores`, `explanation`, `flags` (`V1:446-449`) | n/a | — (deny) | `QualityEvaluationRepository:17` — must be unreachable from any CUSTOMER route |
| **A14** | **Escalation — ANTI-RULE** | **no CUSTOMER access** | Same shape: `escalation.conversation_id` (`V1:238`) hangs off an owned conversation. `reason` (`:239`) is an internal note | n/a | — (deny) | `EscalationRepository:19` — **ownership-blind** |
| A15 | **WebSocket `/topic/conversation/{id}`** | `hasRole('CUSTOMER')` | **The same A6 predicate, at STOMP subscribe time**, not at HTTP GET time. A REST-only check leaves a second wide-open door: `SUBSCRIBE /topic/conversation/<someone else's uuid>` | 2 | `AuthorizeConversationSubscription` (a `ChannelInterceptor`) | none — `host/api/websocket/` is empty |

---

## B. ADVISOR-owned resources

| # | Resource | Coarse gate | Ownership predicate | Hops | Use case | Repository affected |
|---|---|---|---|---|---|---|
| B1 | Own profile — read | `hasRole('ADVISOR')` | `advisor.user_id = P` | 1 | `GetOwnAdvisorProfileQuery` | **ADD** `AdvisorRepository.findByUserId` |
| **B2** | **Own profile — write, field-restricted** | `hasRole('ADVISOR')` | `advisor.user_id = P` **plus writable set = `{status}` only**. **`credit_limit` (`V1:135`) and `max_concurrent` (`:134`) are ADMIN-only.** They sit on the same row as `status`, so one `PUT /advisors/me` that binds the whole entity **lets an advisor raise their own commercial-gesture ceiling** | 1 + allow-list | `SetOwnAvailabilityCommand`, separate from ADMIN's `UpdateAdvisorLimitsCommand` | **never bind a request body onto `Advisor`** |
| B3 | Own skills — read | `hasRole('ADVISOR')` | `advisor_skill.advisor_id = (select id from advisor where user_id = P)` | 2 | `GetOwnSkillsQuery` | none exists — reached via `Advisor.getSkills()`. Safe **iff** the `Advisor` came from B1 |
| B4 | Own skills — **write** | `hasRole('ADMIN')` — **not ADVISOR** | An advisor must never grant themselves a skill or raise their `level` (`V1:144`) — `findEligibleForSkill` routes on `s.level >= :minLevel`, so self-granting level 3 is self-assignment of unqualified work. A **coarse** gate, listed here so it is not mistaken for part of B3 | n/a | `GrantAdvisorSkillCommand` (ADMIN) | — |
| B5 | Assigned conversation | `hasRole('ADVISOR')` | `conversation.advisor_id = (select id from advisor where user_id = P)`. **`advisor_id` is nullable** — a `QUEUED` conversation has it NULL, and `NULL = anything` is not true, so **an unassigned conversation is owned by nobody**. Queue browsing is therefore a different rule (B6) | 2 | `GetOwnAssignedConversationsQuery` | **ADD** `findByAdvisorIdAndStatusIn` — `ConversationRepository` has **no `advisor_id` query at all** |
| B6 | Queue browsing | `hasRole('ADVISOR')` | Not ownership but **capability**: `status='QUEUED'` **and** `conversation.skill_id ∈ (advisor's `advisor_skill` set)`. An advisor should not see a queue they hold no skill for | 3 | `GetOwnQueueQuery` | `findNextToAssign` (`:41-51`) takes `skillId` bare — **no advisor scoping, no `run_id` filter** |
| B7 | Claim a conversation | `hasRole('ADVISOR')` | B6 **plus a concurrency guard**: `QUEUED → ASSIGNED` must be a conditional update (`where status='QUEUED' and advisor_id is null`) so a race yields `CONVERSATION_ALREADY_ASSIGNED` (a frozen code) rather than a silent overwrite. **Plus** `advisor.max_concurrent` must not be exceeded | 3 + state | `ClaimConversationCommand` | **ADD** guarded update + `countByAdvisorIdAndStatusIn` |
| B8 | Messages in own conversations | `hasRole('ADVISOR')` | `app_user.id` = `advisor.user_id`, `advisor.id` = `conversation.advisor_id`, `conversation.id` = `message.conversation_id`. **Structurally identical to A8 through a different column** — a shared helper taking (conversation id, principal) and branching on role is the only way this stays consistent | **3** | `GetAssignedConversationTranscriptQuery` | same four `MessageRepository` methods |
| B9 | Message — write | `hasRole('ADVISOR')` | B8 **plus** `sender` forced `'ADVISOR'`, `ai_generated` set from provenance not from the body | 3 | `PostAdvisorMessageCommand` | — |
| B10 | Credits they granted | `hasRole('ADVISOR')` | `commercial_credit.granted_by = (select id from advisor where user_id = P)`. **Note the asymmetry**: `granted_by` → `advisor(id)` while `approved_by` → `app_user(id)` — **not the same identity space**, so "did I grant this" and "did I approve this" are different joins | 2 | `GetOwnGrantedCreditsQuery` | **ADD** `findByGrantedById...` — does not exist |
| B11 | Grant a credit | `hasRole('ADVISOR')` | Ownership trivially self. The real predicate is the **business ceiling** — see E6 | 2 | `GrantCommercialCreditCommand` | `sumGrantedToCustomerSince` (`:36-37`) |
| **B12** | **Customer panel during a conversation** | `hasRole('ADVISOR')` | **Access to a customer file is conversation-scoped, not global.** Readable only while `exists (select 1 from conversation where advisor_id = <own> and customer_id = X and status in ('ASSIGNED','ACTIVE','ESCALATED'))`. **Without this, `hasRole('ADVISOR')` on `GET /customers/{id}` is a full customer-database read for every advisor** | 3 + state | `GetCustomerPanelForActiveConversationQuery` | **ADD** `existsByAdvisorIdAndCustomerIdAndStatusIn`. ⚠️ post-`RESOLVED` access is a policy call — see S-7 |
| B13 | Own quality evaluations | `hasRole('ADVISOR')` | `quality_evaluation.conversation_id ∈ (conversations where advisor_id = own)`. Seeing one's own scores is defensible; seeing a peer's is a performance-review leak | 3 | `GetOwnQualityScoresQuery` | `QualityEvaluationRepository:17` — ownership-blind. ⚠️ some centres withhold pending review |
| B14 | WebSocket `/topic/queue/{skill}` | `hasRole('ADVISOR')` | Subscribe-time B6 predicate. `CALLVERSE_PROJECT_CONTEXT.md:328` verbatim: *"an advisor only receives their own queue"* | 3 | `AuthorizeQueueSubscription` | none — package empty |

---

## C. SUPERVISOR — and the human-in-the-loop rules

| # | Resource | Gate | Predicate | Use case | Repository |
|---|---|---|---|---|---|
| **C1** | **Approve an agent decision** | `hasRole('SUPERVISOR')` | Write `agent_decision.approved_by = P` (`V1:416`). **Self-attribution: `approved_by` may only be set to the caller's own `P`, never from the request body** — otherwise a supervisor forges another's sign-off on the XAI record. **Plus idempotence**: refuse if already non-null, or the audit trail is silently rewritable | `ApproveAgentDecisionCommand` | `AgentDecisionRepository:23` reads the audit; **no method sets it, and no "pending review" query exists** |
| C2 | **Reject an agent decision** | `hasRole('SUPERVISOR')` | **Not expressible.** See HITL-1 | — | — |
| C3 | Resolve an escalation | `hasRole('SUPERVISOR')` | Write `escalation.resolved_by = P` (`V1:241`), same self-attribution and idempotence. **The FK targets `app_user` with no role constraint** — nothing stops a CUSTOMER id landing there | `ResolveEscalationCommand` | `EscalationRepository:17` — no `run_id` filter |
| C4 | Approve an over-ceiling credit | `hasRole('SUPERVISOR')` | Write `commercial_credit.approved_by = P` (`V1:230`). **Plus separation of duties the schema does not enforce**: reject when `(select user_id from advisor where id = granted_by) = P`. **Nothing stops self-approval today** | `ApproveOverCeilingCreditCommand` | `:42` |
| C5 | Manual reassignment | `hasRole('SUPERVISOR')` | Overwrite `conversation.advisor_id`; should still enforce the **target** advisor's `max_concurrent` and skill match | `ReassignConversationCommand` | **ADD** guarded update |
| C6 | Live supervision / KPIs | `hasRole('SUPERVISOR')` | Not ownership; **mode scoping**: `conversation.run_id IS NULL` | `GetLiveSupervisionKpiQuery` | `findByRunIdIsNullAndStatus` (`:69`) — **the only mode-correct query that exists** |
| **C7** | **Supervisor scope itself** | — | **FINDING — a supervisor is globally unrestricted, and no column can change that.** No `team` table, no `supervisor_advisor` relation, no `advisor.team_id`, no `advisor.supervisor_id`, no region column. `advisor_skill` scopes advisors to skills but has **no supervisor analogue**. A SUPERVISOR sees every queue, advisor, escalation and conversation | — | — |

### Human-in-the-loop — the ESPRIT deliverable

`CALLVERSE_PROJECT_CONTEXT.md:404` defines the supervisor as validating **or rejecting** Workforce Manager recommendations. `agent_decision` is the XAI table carrying `observation JSONB NOT NULL`, `action JSONB NOT NULL`, `reason TEXT` (`V1:413-415`).

**Six rules:** (1) write-`approved_by` is SUPERVISOR-only — coarse; (2) **self-attribution** — fine-grained; (3) **idempotence** — refuse overwrite, since there is no `updated_at`, no version column and no history table, so an overwrite is undetectable; (4) **per-decision, never per-run** — a bulk-approve endpoint makes the claim indefensible to a jury; (5) scope — currently none (C7); (6) `escalation.resolved_by` follows the same four, plus `status` `PENDING → RESOLVED` and `resolved_at` in one transaction — note `escalation.status` has **no CHECK constraint** (`V1:234-235`, a recorded `-- REVIEW:`), so its state machine lives entirely in the method body.

> **HITL-1 — "reject" is not representable. This is the headline.**
> The role definition says validate **or reject**. The schema offers one nullable FK. `approved_by IS NULL` is overloaded across *not yet reviewed* and **reviewed and rejected** — indistinguishable. Consequences: **(a)** there is no pending-review queue — only `findByApprovedByIsNotNull()`, and its inverse is not the pending set; **(b)** a rejection leaves no trace, so a decision the RL took anyway and one a human vetoed are the same row; **(c)** the report cannot state an approval *rate*, because the denominator — decisions actually reviewed — is not recorded. **A jury asking "what happened when the supervisor disagreed with the policy?" has no answer in the data.**
> Needs `review_status VARCHAR(20) CHECK (review_status IN ('PENDING','APPROVED','REJECTED'))`, `reviewed_at TIMESTAMPTZ`, `rejection_reason TEXT`.

> **HITL-2 — approval is not timestamped.** `created_at` records when the *agent* decided; there is no `approved_at`. So *"was the human in the loop, or did they rubber-stamp the run afterwards?"* — the defining question — is unanswerable from the table. Same gap on `commercial_credit`.

> **HITL-3 — approval does not gate the action.** The table records decisions **already taken**. As built this is an after-the-fact audit log, not a gate — and *human-in-the-loop* and *human-on-the-loop* are different claims to a jury. If the intent is that a recommendation waits for approval, the schema needs `applied`/`applied_at` and the runner must block. ⚠️ **A product decision, to be made explicitly before the report is written.**

---

## D. ADMIN

| # | Item | Finding |
|---|---|---|
| D1 | Unrestricted | **CONFIRMED by absence.** No tenant, org, team, region or scope column exists on any of the 26 tables. `CALLVERSE_DB_SCHEMA.md:44` states the intent: *"one role per user. A join table would add complexity for no benefit at this scale."* |
| D2 | **KB publication** | Not ownership but a gate that is easy to lose. `kb_article.published` defaults FALSE (`V1:266`) and `KbArticleRepository:16-19` correctly offers `findByPublishedTrue()` with the javadoc *"Unpublished drafts exist but must never reach retrieval."* **But `KbChunkRepository.findByArticleIdOrderByChunkIndexAsc` (`:16`) does not join `kb_article` and does not check `published`.** The `/internal/kb/search` tool reads **chunks** ⇒ **an unpublished draft's chunks are retrievable and can be quoted to a customer by the AI** |
| D3 | Ceilings and rules | ADMIN-only writes to `advisor.credit_limit`, `sla_policy`, `routing_rule`, `quality_criterion.weight`. Listed because B2 is the mirror rule |
| D4 | **ADMIN ≠ SUPERVISOR** | The schema does not distinguish them: both approver FKs target `app_user` with no role predicate, so **an ADMIN can silently perform every human-in-the-loop action a SUPERVISOR can**, and the audit cannot tell them apart without joining back to `app_user.role` — which must survive a later role change on that user. ⚠️ whether ADMIN *should* approve is undecided |

---

## E. The AI service via `/internal`

`/internal` has **no principal**, so no ownership predicate in the A/B sense. Its authorization is a **business predicate per endpoint**.

| # | Endpoint | Authorization condition | Repository |
|---|---|---|---|
| E1 | `GET /internal/customers/{id}` | Key valid. **No ownership check** — the agent legitimately reads any customer. The real risk is the inverse: **a full customer-record read behind one shared secret.** Must project (`is_simulated`, internal ids stay inside) and be network-unreachable from the internet | `findById` |
| E2 | `GET .../invoices?n=3` | Key valid **plus the A4 three-hop join used for correctness**: fetch via `contract.customer_id = {id}`, **never via a caller-supplied `contract_id`** — otherwise it inherits A4's hole with **no principal to catch it**. `n` bounded server-side | `InvoiceRepository:22` |
| E3 | `GET /internal/network/status?zone=` | Key valid **plus a mode predicate**: `network_incident.run_id IS NULL` for a live customer. **`findByZoneAndResolvedAtIsNull` (`:17`) does not filter it** ⇒ **a simulated outage injected by a scenario would be announced to a real customer as real** | `:17` |
| E4 | `GET /internal/kb/search` | Key valid **plus `kb_article.published = TRUE`** — see D2, the chunk query does not enforce it. `k` bounded | `KbChunkRepository:16` |
| E5 | `POST /internal/tickets` | Key valid **plus a binding predicate the frozen contract cannot carry**: the body's `customer_id` must equal the handled conversation's `customer_id`. `ticket.conversation_id` exists (`V1:210`) but **the endpoint path has no conversation** ⇒ the agent asserts the customer id and **the backend has nothing to check it against**. **Plus** clamp `severity` to 1–5, or the CHECK surfaces as a 500 rather than a business error | `save` |
| **E6** | **`POST /internal/credits` — the ceiling** | Key valid **plus the rolling-total predicate**: `sumGrantedToCustomerSince(customer_id, T) + amount <= advisor.credit_limit`, else `CREDIT_LIMIT_EXCEEDED`. **Three real gaps:** **(a) which advisor's `credit_limit`?** `granted_by` → `advisor(id)`, so the ceiling comes from the conversation's advisor — but `conversation.advisor_id` is **nullable**, and an AI-handled first-line conversation has **none**. **With no advisor there is no ceiling and nothing to compare against.** **(b)** `since` is a parameter with **no source** — no column, config key or document defines the rolling window. **(c)** the sum ignores `is_simulated`/`run_id`, so **simulated grants consume a real customer's allowance** | `sumGrantedToCustomerSince:36` |
| E7 | `POST .../escalate` | Key valid **plus** `raised_by` forced `'AI'` — the CHECK admits only `('ADVISOR','AI','RULE')`, so **a customer-raised escalation is not representable by design**. **Plus** a state predicate (only from `ACTIVE`). **Plus idempotence** — nothing stops ten escalations on one conversation; `escalation` has no uniqueness on `(conversation_id, status)` | `:19`, `save` |

> **Cross-cutting:** a single shared key is **one role with unlimited business scope**. The agent cannot be scoped to "customers in conversations it is handling" because **no column records which agent holds which conversation**. ⚠️ Whether the Customer Advisor gets a real `advisor` row (with a real `credit_limit`) is undecided — **and E6(a) cannot be closed until it is.** See S-8.

---

## F. Simulated actors — mode scoping

**What exists:** `CustomerRepository.findBySimulatedFalse()` (`:26`), `AdvisorRepository.findBySimulatedFalse()` (`:46`), `countBySimulatedTrueAndStatus` (`:48`), `ConversationRepository.findByRunIdIsNullAndStatus` (`:69`). Columns: `customer.is_simulated` (`V1:67`), `advisor.is_simulated` (`:136`), `conversation.run_id` (`:169`).

**Should a real CUSTOMER ever see simulated conversations?** No — **and the customer-side direction is closed by construction**: a simulated customer has `user_id IS NULL`, so predicate A6 can never reach one. **The inverse is open**: nothing constrains `conversation.run_id` to be NULL when `customer.is_simulated = FALSE`. A real conversation can be stamped with a `run_id` and swept into `run_kpi` — **corrupting an ESPRIT result** — and nothing prevents it, because `run_id` is deliberately not a foreign key.

**Mode-blind methods — a separate class of defect from ownership-blind:**

| Method | Line | What leaks |
|---|---|---|
| `ConversationRepository.findNextToAssign` | `:41-51` | **No `run_id` filter.** A live advisor's next conversation can be simulated; a run can consume live traffic. The most heavily used query in the application |
| `countByStatusAndSkillId` | `:54` | Queue depth is *"the Workforce Manager's primary observation"* — mixing universes **poisons the RL observation vector**. An ESPRIT-result defect, not a UI one |
| `AdvisorRepository.findEligibleForSkill` | `:38-41` | Routes live conversations to synthetic advisors |
| `AdvisorRepository.findByStatus` | `:43` | Supervision roster mixes real and synthetic staff |
| `EscalationRepository.findByStatusOrderByCreatedAtAsc` | `:17` | The live supervisor queue fills with simulated escalations |
| `TicketRepository.findByStatusIn` | `:20` | `ticket` has **no** `is_simulated`/`run_id` — exclusion needs a join no method performs |
| `CommercialCreditRepository.findByApprovedByIsNotNull...` | `:42` | The supervisor audit view includes simulated grants |
| `AgentDecisionRepository.findByApprovedByIsNotNull` | `:23` | HITL audit across all runs |
| `NetworkIncidentRepository` | `:17`, `:19` | `run_id` unfiltered — see E3 |
| `InvoiceRepository.findByStatusAndPeriodEndBefore` | `:25` | *"Drives the overdue sweep"* — would dun simulated customers |
| `QualityEvaluationRepository.findByConversationIdInAndEvaluator` | `:23` | The Cohen's κ query, no run scoping |

---

## G. Ownership-blind repository methods — 22

**Class A — user-owned. These are the ones that become CVEs.**
`InvoiceRepository:22` · `TicketRepository:18` · `ConversationRepository:57` · `CommercialCreditRepository:39`, `:44`, `:36` · `MessageRepository:21`, `:24`, `:26`, `:32` · `EscalationRepository:19` · `QualityEvaluationRepository:17`, `:23`

**Class B — experiment/back-office.** `AgentDecisionRepository:18`, `:20` · `MetricSampleRepository:30`, `:33`, `:36` · `ConversationRepository:63` · `SimulationRunRepository:49` · `KbChunkRepository:16`, `:23` (**destructive**, bare id)

> **Class C — the inherited surface, larger than all the above combined.** **22 of 23 repositories extend `JpaRepository`** — the sole exception is `MetricSampleRepository`, which extends the bare `Repository` marker *"so the mistake is a compile error rather than a code-review catch that someone eventually misses"* (`:18-21`). **That reasoning is exactly the ownership argument, applied to writes.** The other 22 inherit `findById`, `findAll`, `deleteById`, `save`, `saveAll` — all ownership-blind, all callable, none reviewed. **`InvoiceRepository.findById(someOtherCustomersInvoiceId)` is the shortest path to the breach, and it appears in no audit list because nobody wrote it.**

**Class D — adjacent.** `AppUserRepository.findByEmailIgnoreCase` does not filter `active`.

*(`docs/BACKEND_STATE_AUDIT.md:231` states 17; this catalogue counts 22 by including the run-scoped and `KbChunk` reads.)*

---

## H. Rules the current schema cannot express — 13

| ID | Rule | Evidence | Needed |
|---|---|---|---|
| **S-1** | *"A user is exactly one customer / one advisor."* | `user_id` nullable and **not UNIQUE** on both | `UNIQUE (user_id)` on both. **Without it the first hop of every rule is ambiguous** |
| **S-2** | *"A supervisor **rejected** this."* | only `approved_by` exists | `review_status` + `reviewed_at` + `rejection_reason`. **Blocks an ESPRIT deliverable** |
| **S-3** | *"This supervisor oversees only team X."* | no team/supervisor relation anywhere | a `team` table or `supervisor_skill`. **Until then every supervisor is global — state it in the 3iL security section rather than let a jury find it** |
| **S-4** | *"`approved_by` must be SUPERVISOR or ADMIN."* | three FKs, no role predicate | A DB CHECK cannot cross tables; needs a trigger, or accept method-body-only. **Document the choice** |
| **S-5** | *"The approver is not the granter."* | `granted_by` → `advisor`, `approved_by` → `app_user` — **different identity spaces, never directly comparable** | method-body only, via a join through `advisor.user_id` |
| **S-6** | *"Which ceiling was in force?"* | `advisor.credit_limit` is mutable and current-valued; no snapshot | `commercial_credit.limit_at_grant`. **Without it, raising a limit retroactively makes past over-ceiling grants look compliant** |
| **S-7** | *"Read the file only while handling the conversation."* | status expresses *currently*, not a grant window | a grant table, or accept ASSIGNED/ACTIVE/ESCALATED only |
| **S-8** | *"Which agent handles this conversation?"* | the AI has no identity in the schema | give the Customer Advisor a real `advisor` row (also closes E6(a)), or add `conversation.handled_by_agent`. **Blocks the only available scoping of the service key** |
| **S-9** | *"Exclude simulated tickets/credits/escalations/evaluations."* | those four tables carry **no** `run_id` and **no** `is_simulated` | `run_id` on them, or joined finders. The `findBySimulatedFalse` pattern stops at two tables |
| **S-10** | *"A live customer's conversation can never belong to a run."* | `run_id` deliberately not a FK, unconstrained against `is_simulated` | trigger or domain invariant. Risk: contaminating `run_kpi`, which *"feeds every chart in the report"* |
| **S-11** | *"The rolling credit window is N days."* | `sumGrantedToCustomerSince(customerId, **since**)` — **no column, config key or document defines `since`** | a config property; ideally a row alongside `sla_policy` |
| **S-12** | *"Who changed this, and when?"* | no audit table, no `updated_at` on the sensitive tables | already listed as cuttable debt — **but cutting it also removes the only detection for the HITL overwrite rule** |
| **S-13** | *"One role per user"* is a constraint for supervisors | `CALLVERSE_DB_SCHEMA.md:44` | a supervisor who also takes calls needs two accounts. **Name it before the demo, not during it** |

---

## I. The five rules most likely to be forgotten

**1 — A4/A8: invoice and transcript, because they are three hops and no query expresses them.**
`findByContractIdOrderByPeriodStartDesc` *reads* as if already safe: it takes a contract id, it is indexed, its javadoc discusses index usage and says nothing about ownership. The developer adds `@PreAuthorize("hasRole('CUSTOMER')")`, tests with their own contract, ships. **Damage: the full billing history of every customer, readable by any authenticated customer, by changing one UUID.** Verbatim the failure the context document predicts, and the demo where a graded 3iL criterion fails live.

**2 — A15/B14: the WebSocket subscription checks, because ownership was already "done" on the REST side.**
Topics ship in Phase 4, ownership is Phase 2 work; by then ownership feels finished. `@PreAuthorize` does not apply to a STOMP `SUBSCRIBE` frame at all, and the websocket package is empty so there is no interceptor for anyone to notice is missing. **Damage: a live streaming feed of any conversation in the system, bypassing every REST check that was correctly implemented — continuous rather than one-shot, and leaving no request log.**

**3 — B2: field-level write on `advisor.credit_limit`.**
Not a *row* ownership rule, so it survives every review that asks "does this check the owner?" — it does, correctly. One idiomatic `PUT /advisors/me` that binds the body onto the entity passes the ownership check and **makes each advisor the author of their own ceiling.** **Damage: it refutes the project's central claim** — *"the backend, never the agent, is the authority"* — in one request, and S-6 means the audit trail retroactively agrees with the tampered value. The same shape applies to `customer.churn_risk` (A2).

**4 — A13/A14: the anti-transitivity rule.**
The one **anti**-rule here. A clean, well-factored `assertOwnsConversation(conversationId, principal)` helper reused across every conversation-keyed resource is *better* engineering than copy-paste — **and it is exactly what opens this hole.** **Damage: customers read the internal performance grading of the advisor who served them**, including `flags` such as `{"unsourced_claims": 2}` — a confession that the system detected the AI making unsourced claims to them. Least likely to be caught by a test, because the test author reasons that the customer owns the conversation.

**5 — E6: the credit ceiling with no advisor to measure it against.**
Everyone knows the ceiling rule — it is stated three times and the query carries a 10-line javadoc. **Precisely because it is so well known, the null path is not considered**: an AI-handled first-line conversation has no assigned advisor, therefore no `credit_limit`. The developer reaches for a fallback, or — under time pressure — skips the check when no advisor is present. **Damage: the one rule the architecture is justified by becomes conditional on a nullable column, and the failure is silent and asymmetric — it fires only for AI-granted credits, the exact path the rule exists to constrain.** Resolve S-8 before Phase 3 ships.
