# Design

## Ports and adapters

- `axiom.execute` — the pure port. Inputs: a validated task
  record (task id, class, admitted scope, capability grant, base
  identity), a proposal, the approved policy, the current lease
  and outbox state read from the ledger, and the deployment
  capability record. Outputs: admission decisions
  (`:admit` / `:deny` / `:defer` with named reasons), projected
  contexts, lease transitions, outbox intents and reconciliation
  plans, patch admission verdicts. Pure and side-effect-free; no
  I/O, no network, no database, no process spawning. The worker
  is never a function argument — only its validated records are.
- `axiom.adapters.worktree` — isolated worktree lifecycle. Creates
  a worktree pinned to the task's base identity, confines all
  worker file effects to it, and destroys it on task completion or
  revocation. It never exposes the supervisor's checkout, the
  ledger file, or credential paths to the worker; path
  confinement is structural (the worktree root is the only
  writable root the adapter reports), not a convention the worker
  is asked to follow.
- `axiom.adapters.agent` — the agent interface plus its two
  implementations. The interface is: `start(task, context) →
  worker-handle`, `propose(handle) → proposal`,
  `act(handle, admitted-proposal) → action-result`,
  `cancel(handle)`. The `:fake` implementation is deterministic
  and scripted from fixtures: it emits a fixed proposal/action
  sequence, including scripted adversarial moves (scope-widening
  text, unauthorized capability requests) so the guarded loop is
  exercised against misbehavior. The `:process` implementation
  runs a bounded OS subprocess in the worktree: argv comes only
  from an admitted proposal, the environment is scrubbed (no
  evaluator credentials, no tokens), the working directory is the
  worktree root, stdout/stderr are captured and schema-validated
  before the supervisor reads them as proposals, timeout kills
  the process, and cancellation is SIGKILL. Both adapters report
  declared identity; the supervisor's logic is identical for
  both.
- `axiom.adapters.pr` — the only new provider-mutating namespace
  besides the 0005 checks adapter, and only for PR creation. Its
  constructor requires a passing R8-style capability record and a
  recorded `:governance/publication-authorized` event; it has no
  code path for merge, close, label, or protection changes. PR
  publication is always invoked through the outbox, never
  directly, so every publication is intent-recorded, idempotent,
  and reconciled.
- The 0004 `axiom.adapters.github` stays read-only; the 0005
  `axiom.adapters.checks` stays the only check-run writer. The
  supervisor never calls provider APIs itself.

## Authorization model

The 0005 roles carry over and one is added:

- `:authorizer/owner` — the repository owner. Authorizes policy
  approvals, verifier-configuration approvals,
  protection/permission changes (0005), and now also
  `:governance/publication-authorized` (R10) and
  `:governance/promotion-accepted` (R11).
- `:evaluator/trusted` — the trusted evaluation workflow. For a
  standard task it is the deployment's configured evaluator; for
  a self-modifying task it must be the pinned previous evaluator
  release (R11) — the candidate's own code is never the
  authority for its own acceptance.
- `:candidate/untrusted` — the PR under evaluation (0005 sense).
- `:worker/untrusted` — the agent run. It authorizes nothing.
  Its proposals are evaluated data; its actions are executed
  only when admitted; its records are accepted only with a
  current fencing token.

Separation is enforced by event kinds, as in 0005:
`:governance/*` events require an authorizer identity and a
digest; `:lease/*` and `:outbox/*` events require the evaluator
identity and the fencing token they were issued under; an event
with a missing or mismatched role identity is `:invalid` and is
rejected by ledger validation, never normalized. "A candidate
version cannot govern its own policy or evaluator upgrade"
(`docs/roadmap.md`) is enforced structurally: the R11
promotion path requires the authorizer event, and the
supervisor refuses to evaluate a self-modifying task under the
candidate's evaluator.

## Proposal protocol

A proposal is data:

- `:proposal/task-id`, `:proposal/worker-id`,
  `:proposal/fencing-token`
- `:proposal/actions` — each action is one of
  `:action/write-file` (paths + content digests),
  `:action/run-command` (argv + declared capability),
  `:action/collect-evidence` (evidence descriptor). No action
  kind executes code supplied by the worker: commands are argv
  vectors run by the adapter, never shell strings, and never
  evaluator code.
- `:proposal/note` — free text for the human reviewer. It is
  never parsed, never used as a key, gate name, path, or policy
  reference; the admission predicates do not read it.

Admission (`axiom.execute/evaluate-proposal`) checks, in order:
(1) fencing token current; (2) task lease held by this worker;
(3) every action within the task's admitted scope; (4) every
action's capability granted on the task; (5) every path
path-safe; (6) class check — no kernel-namespace writes under a
standard task. Any failure denies the whole proposal with the
named reason; partial admission is not offered (the worker
re-proposes). Property, machine-tested in implementation:
adding unsupported agent claims to a denied proposal cannot
turn it into an admitted one (design §13).

## Context projection

`axiom.execute/project-context` is pure: `(task, policy,
ledger-state, budget) → context`. It selects the task's
obligations, the approved policy excerpt, the blocking findings,
and the admitted scope, truncated to the budget by dropping
lowest-priority informational records first. Blocking
obligations are never droppable: a budget too small to hold
them makes projection fail with `:context-budget-too-small`
rather than silently omitting a blocker. The projected
obligation states are copied, not recomputed, so projection
cannot change an authoritative decision — asserted byte-identical
in tests.

## Leases

Lease record: `{:lease/task-id :lease/worker-id
:lease/token :lease/expires-at :lease/issued-by}`. The
single-holder invariant is enforced by the ledger append: an
acquire appends only if no unexpired, unreleased lease for the
task exists in the prefix; the append is transactional (0002),
so two concurrent acquires serialize to exactly one success.
Renewal appends `:lease/renewed` carrying the presented token;
a presented token that does not match the current lease is
rejected and the attempt is recorded as denied, never applied.
Expiry is computed from recorded time, not wall-clock trust:
an expired lease is simply absent from the "current leases"
projection, and any worker action citing its token is
`:stale-fencing-token`. Revocation (`:lease/revoked` by the
evaluator or authorizer) takes effect on the next record read —
in-flight worker actions are fenced at the supervisor, which
checks token currency before executing anything.

## Action outbox

Intent record: `{:outbox/idempotency-key :outbox/task-id
:outbox/action :outbox/payload :outbox/fencing-token
:outbox/state :outbox/attempts}`. States:
`:intent-recorded → :executing → :executed | :failed`, with
`:uncertain` for "the write may have happened" (timeout after
submit, crash between submit and confirmation). Only the
supervisor transitions intents; the worker may only submit
proposals that the supervisor turns into intents. The
idempotency key is `(task-id, action, payload-digest)`:
resubmission of the same key returns the existing intent,
never a second execution.

Reconciliation (`axiom.execute/reconcile-outbox`, pure over
`(outbox-state, provider-state)`): on startup the supervisor
replays the outbox; `:executed` intents are left alone;
`:failed` intents are reported as blockers; `:uncertain`
intents are resolved by a provider query (did the check run /
PR with this idempotency key get created?) and then marked
`:executed` or re-driven as a fresh intent — never re-executed
blindly. The fake provider double in tests makes the query
observable, so exactly-once is machine-checked.

## Fencing

The fencing token is issued at lease acquire and renewed with
every renewal; the supervisor keeps the current token per
task. Every worker-submitted record (proposal, action result,
evidence claim) carries the token the worker holds; the
supervisor compares it to the current token before doing
anything with the record. Mismatch → deny with
`:stale-fencing-token`, and the record is logged, not applied.
A worker whose lease expired, was revoked, or was reassigned
therefore cannot publish, admit patches, or move outbox
intents — its tokens are simply old.

## Capability dispatch

The grant is data on the task record, e.g.
`{:capability/read-file #{<paths>},
:capability/write-file #{<paths>},
:capability/run-tests true, :capability/shell false}`.
`axiom.execute/check-capability` is a pure predicate over
`(grant, request)`; the adapter calls it before every worker
action and refuses on false. `:capability/shell` defaults to
false and is only true when the task's admitted scope
explicitly includes it and the deployment capability record
permits process execution. Requests for undeclared
capabilities — the adapter has a closed set — are rejected as
`:unknown-capability`, never passed through.

## Patch admission

`axiom.execute/admit-patch` takes the worktree diff (as a list
of path operations with digests, produced by the worktree
adapter — the supervisor never trusts the worker's own
description of what changed), the approved proposals, the
lease state, and the task class. Checks: (a) every changed
path covered by an admitted proposal; (b) lease current,
fencing token matches; (c) path safety — the 0002/0003 rules
(no `..` traversal, no symlink escape, no submodule
injection, renames enumerated as delete+add); (d) class check
— kernel-namespace writes require `:task-class/self-modifying`
and the R11 promotion path. Then post-action verification
runs the pinned verification recipe (the 0003 runner shape:
timeout, cancellation, evidence marked at its actual trust
level) and records the evidence. Admission is a gate
evaluation under the approved policy: the verdict is one of
`:allow` (publishable, pending R10 authorization),
`:deny` / `:defer` with named reasons. An unadmitted patch is
never published and never becomes the basis for a promotion.

## Agent adapters

The interface is small on purpose; everything security-
relevant lives in the supervisor. The fake adapter
(`:adapter/fake`) replays a scripted action sequence from the
fixture: proposals, action results, and evidence claims are
fixed data, including scripted adversarial moves (a
scope-widening proposal, an unauthorized shell request, a
stale-token resubmission) so the guarded loop is exercised
against misbehavior deterministically. The process adapter
(`:adapter/process`) spawns the worker as an OS subprocess:
argv from the admitted proposal only, environment scrubbed of
evaluator credentials and tokens, working directory confined
to the worktree, wall-clock timeout with SIGKILL on expiry,
cancellation via SIGKILL on supervisor cancel, stdout/stderr
captured and validated against the proposal/output schema
before the supervisor reads them. Budgets — max cost units,
max attempts, max wall time — are enforced by the supervisor,
not the adapter: on exhaustion the run stops and the task
records the actionable blocker `:budget-exhausted`.

## PR publication

Publication is an outbox action `:action/publish-pr` with
payload `(repo, base, head, title, body, patch-digest)`,
executed by `axiom.adapters.pr` only when the outbox intent
exists, the fencing token is current, and a
`:governance/publication-authorized` event naming the exact
patch digest is in the ledger. The adapter creates the PR and
records the provider's PR identity back into the outbox
intent; idempotency is per (task, patch-digest), so a
reconciled retry never opens a duplicate PR. The adapter has
no merge operation — merge is out of scope, and the design
treats a merge code path here as a defect.

## Policy promotion gates (self-development)

A self-modifying task records, at admission: the pinned
previous evaluator release digest (`:promotion/base-evaluator`),
the task class, and the authorizer who approved the task's
admission. Verification evidence for such a task is produced
by running the pinned evaluator's verification recipe, and
the evidence record names that evaluator release — the
candidate's own code never appears as the verifier. The
independently specified corpus (adversarial + holdout cases
not authored by the candidate) is the acceptance basis; the
candidate's own tests are supporting evidence only. Promotion
is the authorizer's `:governance/promotion-accepted` event
naming the patch digest and the evidence bundle digest. Until
that event exists, the patch is not published and not
promoted; the known-good evaluator stays the authority and
the rollback procedure (re-point the deployment at the
pinned release) is preserved. The first self-driven task
after 0006 must be a bounded documentation or adapter change
outside the trust-critical kernel, per `docs/self-hosting.md`.

## Ledger integration

New payload kinds through the 0002 append path, additive to
the existing schema (schema v5, forward-only): `:task/accepted`,
`:task/context-prepared`, `:task/completed`, `:task/blocked`,
`:proposal/admitted`, `:proposal/rejected`,
`:lease/acquired`, `:lease/renewed`, `:lease/released`,
`:lease/expired`, `:lease/revoked`,
`:outbox/intent-recorded`, `:outbox/executed`,
`:outbox/failed`, `:outbox/uncertain`,
`:patch/admitted`, `:patch/rejected`,
`:evidence/verification-recorded`,
`:governance/publication-authorized`,
`:governance/promotion-accepted`. The 0002 invariants apply
unchanged: transactional sequence, hash chain,
event-id/dedup-key dedup, rebuildable snapshots with
replay-equivalence as the hard invariant. Replay of a prefix
reproduces the task lifecycle verbatim, including which
evaluator release produced each decision — replay never
re-executes a worker or re-runs an adapter.

## Budgets and cancellation

Budgets are task data: `:budget/max-cost`,
`:budget/max-attempts`, `:budget/max-wall-seconds`. Cost is an
abstract unit the adapters report per action (the fake
adapter reports fixed costs; the process adapter reports
elapsed time as cost). Every admitted action decrements the
remaining budget; exhaustion stops the run with
`:budget-exhausted` and the task is recorded `:task/blocked`
with the actionable blocker named. Cancellation is
supervisor-initiated: the task is marked cancelled in the
ledger, the lease is revoked, the adapter kills the worker,
and in-flight intents are reconciled (uncertain ones by
query, never by assumption).

## CLI

- `run-task --task TASK-EDN --adapter fake|process` — drives
  one synthetic task end-to-end through the guarded loop:
  admission, context projection, lease acquire, proposal loop,
  patch admission, post-action verification, and — only with a
  recorded `:governance/publication-authorized` event — outbox
  PR publication through the fake provider. Emits the EDN task
  report (decisions, reasons, evidence digests, evaluator
  release). The only command that may mutate provider state is
  the authorized publication step, and in `scripts/check` it
  runs against the fake provider with zero network.
- Exits: 0 valid report, 4 invalid input (malformed task EDN,
  unknown adapter, unsatisfiable scope), 5 operational failure
  (worktree failure, lease store unavailable, budget
  exhaustion is reported as a blocker in the report with exit
  0 — the run completed, the task did not).
- The CLI never executes worker code in-process: the process
  adapter spawns subprocesses; the fake adapter is data.

## Fixtures and offline reproduction

The port defines a synthetic fixture format: tasks
(`synth-task-*` with scope, class, grants, budgets), fake-
agent scripts (including adversarial scripts), worktree
base identities, lease/outbox states, and a fake provider
double for outbox reconciliation and PR publication. A
complete synthetic task — docs change, standard class —
runs through the guarded loop offline and replays
byte-identically. All repository identities, SHAs, logins
and tokens are invented (`synth-*`); no live credentials.

## Out of scope (explicit)

Automatic merge or any merge capability — an allow decision
is not a merge instruction. Vendor-specific coding-agent API
integrations (the process adapter is the one real adapter).
Consumer-specific tasks, policies, or evidence. Live-network
tests. Worker sandboxing proofs beyond the scrubbed-
environment process adapter. Signatures or tamper-proofing
beyond the 0002 tamper-evident ledger. The first actual
self-driven Axiom PR — that is the 0006 implementation's
readiness gate to satisfy, not this spec's deliverable.
