# 0006 — Supervised agent execution (self-hosting loop)

Status: Accepted (2026-09-20) — requirements, design, tasks and
acceptance gates are recorded. Verification is pending implementation.

Authority: the repository owner's standing autopilot instruction for
Axiom (one focused branch/PR per bounded slice under the spec-driven
sequence: requirements → design → tasks → implementation →
verification), and the owner's 2026-09-20 self-hosting goal: "Once
axiom is mature enough, I want to use it to drive its own
development." This records implementation scope, not an independent
security review or v1 acceptance. The larger design remains proposed.

## Spec naming and scope decision (recorded 2026-09-20)

Specs 0001–0005 are Verified and the M1–M4 milestone gate reviews are
Complete. This spec covers the design's M5 item list — Supervised
agent execution: context projection, structured proposal protocol,
fake-agent adapter, isolated worktrees/workers, capability dispatch,
action outbox, leases and fencing, patch admission and post-action
verification, one real agent adapter with timeout/cancellation/output
validation/budgets, and PR publication only when separately
authorized. It also carries the two items the earlier specs
explicitly deferred to M5+: the `leases` and `action_outbox` items
deferred by 0002, and the self-hosting policy promotion gates
deferred by 0005. Per `docs/self-hosting.md`, stage 4 ("Bounded
self-development") is what this spec enables; the first actual
self-driven PR remains after this spec is implemented and its
readiness gates are met. This spec does NOT cover automatic merge
(a distinct optional capability, out of scope — an allow decision is
not a merge instruction), vendor-specific coding-agent API
integrations, or the M6 consumer pilot / v1 release validation.

## Repository boundary (owner instruction, 2026-09-20)

The Axiom repository stays consumer-agnostic. Consumer-specific
contracts, policies, tasks, fixtures, scenarios and verification
evidence belong in the consumer repository, never here. All fixtures
and examples in this spec are synthetic and invented for tests
(`synth-*`). No live credentials, tokens or real repository
identities may appear in this repo's fixtures or evidence.

## Requirements

- R1: Structured proposal protocol. Before performing any action,
  the worker emits a *proposal*: declarative data describing the
  intended actions — file writes (paths and digests), commands (argv
  and the declared capability they consume), evidence to collect.
  The supervisor admits or rejects each proposal against the task's
  admitted scope and the approved policy before any action runs. An
  action with no covering approved proposal is never executed.
  Prompt text — from the task description, the worker's output, or
  any candidate-controlled string — is validated as data and can
  never widen scope, rename a gate, or authorize an action; an
  attempt is rejected with the named reason
  `:prompt-scope-escape`. Testable: a proposal containing an
  out-of-scope action is rejected before any action executes; the
  property "adding unsupported agent claims cannot turn deny/defer
  into allow" (design §13) holds over the proposal evaluator.
- R2: Context projection. The supervisor prepares the worker's
  context as a pure function of (accepted task, approved policy,
  ledger state): bounded by a declared size budget, mandatory
  blockers preserved under the budget (design §13: "Mandatory
  blockers preserved under token limit"), and projection never
  changes an authoritative decision — the projected obligation
  states are identical to the source states. The worker never
  influences what it is shown beyond the task record. Testable:
  projecting a task under a tiny budget keeps every blocking
  obligation; obligation states are byte-identical before and
  after projection.
- R3: Isolated workers and worktrees. Worker execution happens only
  in an isolated worktree — a fresh checkout of the pinned base
  identity, separate from the supervisor's own checkout and from
  every other task's worktree. The worker is untrusted code: it
  receives no evaluator credentials, no ledger write access, no
  policy-store write access, and no ambient authority; every effect
  it has flows through the proposal and capability path. Testable:
  a worker attempt to read a credential path or write outside its
  worktree is denied by capability dispatch (R5); two tasks never
  share a worktree identity.
- R4: Task classes. Tasks carry a class: `:task-class/standard`
  (documentation, examples, adapters — outside the trust-critical
  kernel) or `:task-class/self-modifying` (changes to the kernel,
  policies, the evaluator, or the verification recipes). A standard
  task whose admitted patch touches kernel namespaces is
  reclassified and blocked pending the R11 promotion review; it is
  denied with `:self-modification-requires-promotion`, never
  published. Testable: a patch touching `axiom.nomos` under a
  standard task is denied with that reason.
- R5: Capability dispatch. The worker may invoke only declared
  capabilities (e.g. `:capability/read-file`,
  `:capability/write-file`, `:capability/run-tests`,
  `:capability/shell`). The grant is data on the task record, set
  by the supervisor at task admission, never by the worker. Each
  invocation is checked against the grant: an unknown or ungranted
  capability is rejected with the named reason
  `:unauthorized-capability` and never executed;
  `:capability/shell` is denied by default and requires an explicit
  grant. Testable: a worker requesting shell without a grant is
  rejected and no process is spawned (assertable through the fake
  adapter's refusal record).
- R6: Leases and fencing. A task lease is a ledger-recorded grant
  `(task-id, worker-id, fencing-token, expiry)` with the event
  kinds `:lease/acquired`, `:lease/renewed`, `:lease/released`,
  `:lease/expired` through the 0002 append path. Two runs cannot
  mutate the same leased task concurrently: acquiring a lease on a
  currently leased task is denied with `:task-already-leased`;
  renewal requires presenting the current fencing token and extends
  the expiry; expiry (or explicit release/revocation) frees the
  task for reassignment. Every worker-submitted record carries its
  fencing token; a token that does not match the task's current
  lease makes the action `:stale-fencing-token` and denies it —
  stale workers cannot publish, mutate, or admit patches.
  Testable: two concurrent acquire attempts yield exactly one
  lease; an action with a superseded token is denied with the
  named reason.
- R7: Action outbox (the 0002-deferred item). Every externally
  visible action — provider writes, PR publication — is recorded
  as an intent in the action outbox before execution, executed
  only by the supervisor (never by the worker), and reconciled
  afterward: intent → `:executed` / `:failed` / `:uncertain`.
  Crash recovery: on restart the supervisor replays the outbox; an
  intent whose external effect is confirmed is marked executed and
  never re-executed; an uncertain intent is reconciled by querying
  the provider (idempotent operations only), never by blind retry.
  Duplicate logical effects are impossible: every intent carries
  an idempotency key and the supervisor deduplicates on it.
  Testable: a simulated crash after external success reconciles
  to exactly-once execution; a duplicate intent submission is
  deduplicated; an uncertain intent is resolved by query, not by
  re-execution.
- R8: Patch admission and post-action verification. Worker-produced
  patches (the isolated worktree's diff against the pinned base)
  are admitted only after all of: (a) an approved proposal
  covering the changed paths; (b) a current lease with a matching
  fencing token; (c) path-safety admission — no traversal, no
  symlink escape, no submodule injection (the 0002/0003 path
  rules); (d) post-action verification runs the pinned
  verification recipe and records its evidence in the ledger.
  Patch admission is itself a gate evaluation under the approved
  policy; an unadmitted patch is never published. Testable: a
  patch containing a symlink escape is denied with
  `:path-safety-violation`; a patch with no covering proposal is
  denied with `:no-approved-proposal`.
- R9: Agent adapters. The supervisor talks to workers only through
  the agent adapter interface; it distinguishes adapters by declared
  identity, never by behavior. The fake-agent adapter is
  deterministic and scripted from fixtures for the offline loop.
  The one real agent adapter runs a bounded OS subprocess in the
  isolated worktree with: a wall-clock timeout, cancellation
  (SIGKILL on cancel), output validation (schema-checked
  proposals/output), and cost/attempt budgets. Budget exhaustion —
  cost, attempts, or time — stops the run and reports an
  actionable blocker (`:budget-exhausted`), never a silent partial
  result. Testable: the fake adapter completes the synthetic task
  through the guarded loop; the process adapter kills a runaway
  worker at the timeout and records the cancellation; an
  over-budget run stops with the blocker named.
- R10: PR publication only when separately authorized. Opening a
  PR — or publishing any provider-visible artifact — is a
  supervisor-executed outbox action that requires a separate
  authorization event (`:governance/publication-authorized`
  naming the task id, the exact patch digest, and the authorizer
  identity) distinct from the task lease. Without it the
  supervisor refuses with `:publication-not-authorized` and the
  patch stays local. Automatic merge remains a distinct optional
  capability and is out of scope: an allow decision is not a
  merge instruction (0005's exclusion, restated here).
- R11: Policy promotion gates for self-development. When the
  candidate — the worker's patch — modifies Axiom's own kernel,
  policies, evaluator, or verification recipes
  (`:task-class/self-modifying`), the candidate cannot govern its
  own acceptance: verification runs under the pinned previous
  evaluator release, never the candidate's code; tests generated
  or altered by the candidate cannot be the sole acceptance basis
  (the independently specified corpus decides); promotion
  requires an independent maintainer acceptance event
  (`:governance/promotion-accepted` with the `:authorizer/owner`
  identity from 0005). Until promotion, the new code is never the
  authority for its own gate decision, and the known-good
  evaluator and rollback procedure are preserved
  (`docs/self-hosting.md` bootstrap trust). Testable: a
  self-modifying patch verified only by its own tests is denied
  promotion with `:candidate-tests-insufficient`; the verification
  evidence records which evaluator release ran it.
- R12: Ledger integration. Task lifecycle events — task accepted,
  context prepared, proposal admitted/rejected, lease events,
  capability invocations, action intents and executions, patch
  admitted, verification evidence recorded, promotion events —
  are recorded through the 0002 append path as additive payload
  kinds (`:task/*`, `:lease/*`, `:outbox/*`,
  `:governance/promotion-accepted`) with the 0002 invariants
  (transactional sequence, hash chain, event-id/dedup-key dedup,
  replay-equivalence of snapshots). Replay of a ledger prefix
  reproduces the task lifecycle verbatim, including which
  evaluator release produced each recorded decision.
- R13: Core purity and adapter boundary. The execution core — a
  new pure `axiom.execute` port (proposal evaluation, context
  projection, lease/fencing logic, outbox reconciliation, patch
  admission, capability checks) — stays pure and side-effect-free.
  Side effects live only in explicit adapters:
  `axiom.adapters.worktree` (isolated worktree lifecycle),
  `axiom.adapters.agent` (fake and process agent adapters), and
  the 0005 Checks API adapter plus a new `axiom.adapters.pr`
  adapter (PR creation only — no merge code path) for the R10
  publication path. The worker process is untrusted code: its
  output is validated data, never evaluated as policy and never
  executed as code (the 0001 rule, extended). Unknown or malformed
  required inputs cannot admit actions.
- R14: Honest trust levels and limits. The fake agent proves the
  guarded loop's shape — leases, fencing, outbox reconciliation,
  capability rejection, proposal validation — not real-agent
  reliability. Prompt-injection resistance is tested against
  synthetic adversaries (scope-widening text, supervisor
  impersonation, gate renaming), not claimed as proof against
  real prompt injection. The process adapter runs real
  subprocesses with a scrubbed environment and no ambient
  credentials; it is not a sandboxing proof. Test evidence is
  bounded synthetic observation, not formal proof and not a
  production trust attestation. No live-network tests in
  `./scripts/check`.

## Explicit boundaries

No automatic merge: an allow decision is not a merge instruction;
merge remains a separate, explicitly authorized capability (M6),
never implied by this spec. No consumer-specific task content:
tasks, proposals and fixtures are synthetic and generic; real
consumer tasks live in consumer repositories. No live-network
tests: the fake Checks API (0005) and in-memory provider doubles
drive the offline loop; no live credentials or real repository
identities in fixtures. No vendor-specific coding-agent API
integrations: the process adapter is the one real adapter; deeper
agent integrations are deferred. The first self-driven task, once
0006 is implemented, must be a bounded documentation or adapter
change outside the trust-critical kernel (the
`docs/self-hosting.md` readiness gate); kernel self-modification
follows the R11 promotion path. No change to 0001–0005 decision
bytes, exit contracts or ledger schema semantics beyond additive
`:task/*`, `:lease/*`, `:outbox/*` payload kinds. If host
execution cannot be configured safely, the supervisor reports
advisory mode (0005 R8, restated) instead of claiming supervised
execution it cannot enforce.
