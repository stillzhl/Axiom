# Verification record

## Spec acceptance evidence — 2026-09-20

This slice authored spec 0006 (M5 — supervised agent execution,
self-hosting loop) to Accepted. Docs-only: the change adds
`specs/0006-supervised-execution/` (requirements.md, design.md,
tasks.md, verification.md, acceptance.md) and updates the
`docs/roadmap.md` M5 row. No changes under `src/`, `test/` or
`scripts/`.

Evidence:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **214 tests,
  2154 assertions, 0 failures, 0 errors** — exactly the 0005
  baseline; all 0001–0005 CLI gates pass unchanged. This is the
  inertness baseline: the spec PR changes no code and no test
  counts.
- Spec acceptance gates in `acceptance.md` are all checked for the
  spec slice; implementation acceptance gates are recorded as pending.
- No HomeKV-specific content: the only consumer mentions are the
  standard repository-boundary statements ("nothing HomeKV-specific,
  never here"), consistent with prior specs. All fixtures referenced
  are synthetic (`synth-*`).
- `docs/roadmap.md`: M5 row updated to "Accepted (0006):
  supervised agent execution — context projection, proposal
  protocol, fake + process agent adapters, isolated workers,
  capability dispatch, leases/fencing, action outbox, patch
  admission, post-action verification, separately-authorized PR
  publication, self-development policy promotion gates; automatic
  merge remains out of scope; implementation next". The 0006 scope
  is the design's M5 item list plus the two items earlier specs
  deferred to M5+ (the 0002 `leases`/`action_outbox` items and the
  0005 self-hosting policy promotion gates), so the row update is
  honest about that inclusion.

Claims: nothing is claimed Verified by this slice; no M5 milestone
acceptance is claimed. Spec status is Accepted (2026-09-20);
implementation (tasks T1–T8) is pending.

## Slice 1 evidence — T1–T2 (2026-09-20)

- New pure port `src/axiom/execute.clj`: proposal model and
  `evaluate-proposal` (spec check order; whole-proposal deny with a
  named reason; `:proposal/note` never read; directive-shaped
  action fields quarantined as `:prompt-scope-escape`; malformed
  input → `:invalid`), the task lifecycle state machine
  (`transition-task`), and pure `project-context` (mandatory
  blockers never droppable → `:context-budget-too-small`;
  obligation states copied byte-identically).
- `test/axiom/execute_test.clj` (17 deftests): named-reason denials
  for fencing/holder/scope/capability/path-safety/class; the
  machine-tested property "unsupported agent claims cannot turn
  deny into admit" (denied corpus × adversarial mutations);
  lifecycle transitions and illegal-transition rejection;
  projection blockers-under-tiny-budget and byte-identical states.
- `./scripts/check` unit gates on `feat/0006-execute-port`:
  **231 tests, 2284 assertions, 0 failures, 0 errors**
  (Temurin 17.0.20, Clojure 1.12.0). All fixtures `synth-*`; no
  live credentials, no network.
- Honest limits: lease records are ledger-projected fixtures here;
  the real 0002 append path and schema v5 migration land in T3.
  The prompt-escape scan is heuristic input quarantine, tested
  against synthetic adversaries only (R14).

## Slice A evidence — T3 (2026-09-21)

- `ledger/record-task` and `ledger/record-lease`: strict
  per-kind validation of the five `:task/*` and five
  `:lease/*` kinds (unknown kind/field/missing field →
  `:invalid`, never writable); task kinds are additive —
  `extract-events` shows they contribute zero 0001 world
  events, so the 0001 fold is unchanged. The existing v4
  migration test was updated to v5 (fresh = 5; v4 → v5 keeps
  stored payloads byte-identical).
- Pure `axiom.execute` lease fold: `current-leases` projection
  plus `acquire-lease` / `renew-lease` / `release-lease` /
  `revoke-lease` decisions and `check-fencing-token`
  (renewal with a wrong token denied; superseded tokens
  denied with `:stale-fencing-token`; proposals without a
  lease denied before the fence check).
- `axiom.store`: `current_leases` sidecar (schema v5,
  forward-only; fresh and v1→v5 migrations tested), lease
  events and sidecar rows written in one transaction;
  `acquire-lease!` / `renew-lease!` / `release-lease!` /
  `revoke-lease!` / `expire-leases!` run in `BEGIN IMMEDIATE`
  transactions so two concurrent acquires serialize to
  exactly one lease (the deferred-transaction
  SHARED→RESERVED upgrade deadlocked as immediate
  `SQLITE_BUSY`; `BEGIN IMMEDIATE` fixed it — verified by a
  real two-thread adversarial test); `rebuild-leases!`
  replays the event prefix and rebuilds the sidecar
  byte-identically, proving the sidecar is a pure function
  of the ledger.
- New tests: `ledger_0006_test` (6 deftests), `execute_lease_test`
  (8 deftests), `store_lease_test` (10 deftests) — 24 new deftests
  in total, matching the 231 → 255 suite delta.
- `./scripts/check` on `feat/0006-lease-fencing`:
  **255 tests, 2466 assertions, 0 failures, 0 errors**
  (Temurin 17.0.20, Clojure 1.12.0); all CLI gates green
  (0002–0005 replays, bundles, observations, gates), no
  network, all fixtures `synth-*`.
- Honest limits: expiry is driven by an explicit sweep
  (`expire-leases!`), not a background thread; the T4
  action-outbox reconciliation has not landed yet; fencing
  validation at the T4/T5/T6 call sites (publish, patch
  admission, outbox moves) is enforced by those slices,
  not yet wired here. Note the fence check is first in
  `evaluate-proposal`'s spec order: it returns `:no-lease-held`
  itself when no lease exists.

## Slice B evidence — T4 (2026-09-21)

- `ledger/record-outbox`: strict per-kind validation of the four
  `:outbox/*` kinds (`:outbox/intent-recorded`, `:outbox/executed`,
  `:outbox/failed`, `:outbox/uncertain`) — unknown kind, missing
  field, unknown field, non-keyword action, non-map payload or a
  negative `:outbox/attempt` are `:invalid` and never writable.
  Outbox events are additive: `extract-events` shows they
  contribute zero 0001 world events, so the 0001 fold is
  unchanged.
- Pure `axiom.execute` outbox logic: `outbox-idempotency-key`
  (deterministic over `(task-id, action, payload-digest)`),
  `outbox-intents` (the replay fold), `record-intent` (duplicate
  logical intents dedup on the key — terminal or not — never a
  second execution), `transition-intent` (supervisor-only:
  `:outbox/issued-by` must equal the lease's
  `:lease/issued-by`; stale fencing tokens denied;
  `:stale-fencing-token`, `:not-supervisor`, `:no-lease-held`,
  `:unknown-intent`, `:illegal-transition` all named), and the
  pure crash-recovery planner `reconcile-outbox` over
  `(outbox-state, provider-state)`. `:executing` is the
  supervisor's transient local state — never a ledger event.
  Re-drives carry the intent identity forward under the same
  idempotency key with a bumped `:outbox/attempt`, which also
  keeps the 0002 dedup key distinct per drive.
- `axiom.store`: `record-intent!` / `transition-intent!` /
  `outbox-state` run in `BEGIN IMMEDIATE` transactions against
  the in-transaction outbox fold and the current-leases sidecar,
  appending through the 0002 path (transactional sequence, hash
  chain, dedup). Event ids and dedup keys are derived
  deterministically from the idempotency key's digest (the raw
  key contains `:` which is not a valid 0002 id); the attempt
  is part of the dedup key so a crash-retry stays idempotent
  while a re-drive is a distinct record.
- New tests: `execute_outbox_test` (5 deftests),
  `store_outbox_test` (9 deftests) — 14 new deftests in total,
  matching the 255 → 269 suite delta (2466 → 2599 assertions).
  Every identity is `synth-*`; no network, no live
  credentials.
- T4 acceptance, machine-checked against a fake provider
  double that records queries and executions separately:
  (1) simulated crash after external success reconciles to
  `:mark-executed` — exactly one provider write, one query,
  never re-executed, and a second reconciliation is a no-op;
  (2) duplicate intent submissions (sequential and two-thread
  concurrent, `BEGIN IMMEDIATE` serializing) yield exactly one
  `:outbox/intent-recorded` event; (3) an `:uncertain` intent
  is resolved by provider query — effect present →
  `:mark-executed` with zero additional executions, effect
  absent → `:re-drive` under the same idempotency key — never
  by blind re-execution.
- `./scripts/check` on `feat/0006-action-outbox`:
  **269 tests, 2599 assertions, 0 failures, 0 errors**
  (Temurin 17.0.20, Clojure 1.12.0); all CLI gates green
  (0002–0005 replays, bundles, observations, gates), no
  network, all fixtures `synth-*`.
- Honest limits: the reconciliation plan is pure; the
  supervisor loop that drives it (query → plan → apply) is
  caller-side and lands with the T8 `run-task` wiring. The
  provider query interface is a test double here; the real
  provider adapters are consumer-side.

## Slice C evidence — T5 (2026-09-21)

- Grant model on the task record: `:task/accepted` accepts an
  optional `:task/capabilities` map, validated strictly — keys
  must belong to the closed `axiom.ledger/capability-set`
  (`:capability/read-file`, `:capability/write-file`,
  `:capability/run-tests`, `:capability/shell`); `read-file`,
  `run-tests`, `shell` are booleans; `write-file` is a non-empty
  set of path-prefix strings. Unknown capabilities, wrong value
  shapes, or a non-map grant are `:invalid` and never writable.
  The closed set's canonical definition moved to `axiom.ledger`
  (it validates recorded data); `axiom.execute/capability-set`
  is a re-export, so the proposal evaluator's `:unknown-capability`
  / `:unauthorized-capability` denials are unchanged.
- New explicit adapter `axiom.adapters.worktree` (R3): isolated
  worktree lifecycle with a deterministic identity
  `wt-<16 hex of SHA-256(task-id "/" base-identity)>` pinned to
  the task's base identity (two tasks never share an identity;
  re-creation is idempotent); `create-worktree` (required
  task-id, base-identity, root; optional seed directory copied
  in as the test seam for the pinned checkout),
  `resolve-path` (pure lexical confinement — `..` above the
  root, absolute paths and blank paths are refused with
  `:path-escape` and never resolved), and `destroy-worktree`
  (recursive delete, idempotent, and guarded so only the
  worktree's own `root/id` path is ever deleted — a forged
  worktree map yields `:malformed` and deletes nothing).
  Symlink analysis stays at T6 patch admission; the proposal
  path-safety check is lexical, as before.
- New tests: `worktree_test` (4 deftests — identity
  determinism/uniqueness, create lifecycle incl. seed copy and
  malformed-input refusals, structural confinement escapes,
  guarded idempotent destruction) and
  `task-accepted-capability-grant` in `ledger_0006_test`
  (well-formed grant records; seven malformed grant shapes —
  unknown capability, non-set/empty/non-string write-file,
  non-boolean shell/run-tests, non-map — are `:invalid`).
  The T1/T2 `check-capability` and `evaluate-proposal` denial
  tests already machine-check: shell without a grant →
  `:unauthorized-capability` (the evaluator is pure, so no
  process can be spawned on a denied request), unknown
  capability → `:unknown-capability`, write outside the granted
  prefixes refused, and the adversarial corpus still cannot
  turn deny into admit. Every identity is `synth-*`; worktrees
  live under temp dirs and are destroyed afterwards; no
  network, no live credentials.
- `./scripts/check` on `feat/0006-capability-worktree`:
  **274 tests, 2649 assertions, 0 failures, 0 errors**
  (Temurin 17.0.20, Clojure 1.12.0); all CLI gates green
  (0002–0005 replays, bundles, observations, gates), no
  network, all fixtures `synth-*`.
- Honest limits: the grant is validated at record time and
  enforced by the pure proposal evaluator; the supervisor
  loop that creates the worktree and runs the worker inside
  it lands with the T7 agent adapters and the T8 `run-task`
  wiring. The seed-copy seam stands in for the pinned
  checkout; a real git worktree population is caller-side.

## Slice D evidence — T6 (2026-09-21)

- Pure `axiom.execute/admit-patch` (R8): admission verdict over
  (task, worktree diff, admitted proposals, lease,
  presented token, approved policy). The diff is a list of
  `:diff/path` / `:diff/op` (`:add`/`:modify`/`:delete`) /
  `:diff/digest` / `:diff/symlink?` operations produced by the
  worktree adapter — never the worker's own description. Checks
  in order: shape (`:invalid`); approved
  `:governance/policy-approved` policy
  (`:no-approved-policy`); lease/fencing (`:no-lease-held` /
  `:stale-fencing-token`); path safety — traversal, symlink
  escape, submodule injection (`:path-safety-violation`);
  every changed path covered by an admitted proposal
  (`:no-approved-proposal`); kernel-namespace writes under a
  standard task (`:self-modification-requires-promotion`).
  Verdicts are `:allow` (with the content digest and the task's
  pinned verification recipe), `:deny` with a named reason, or
  `:invalid`; partial admission is never offered.
- `axiom.adapters.worktree/diff-worktree`: diffs the worktree
  against the pinned base directory — added/modified/deleted
  detection by content digest, symlinks flagged with
  `:diff/symlink? true` and no digest (their target is never
  followed). Malformed input and a missing base are named
  refusals; the base is read, never written.
- `ledger/record-patch`: strict `:patch/admitted` and
  `:patch/rejected` events through the 0002 append path.
  Admitted requires the task id, the exact patch digest, the
  post-action verification evidence digest, and the evaluator
  identity; rejected names the denial reason. Patch events are
  additive provenance — `extract-events` shows zero 0001 world
  events.
- New tests: `patch_test` (4 deftests — the `:allow` happy
  path with digest and pinned recipe; all seven named denials
  incl. symlink escape → `:path-safety-violation`, uncovered
  path → `:no-approved-proposal`, standard-task kernel patch
  → `:self-modification-requires-promotion` and the same
  patch allowed under `:task-class/self-modifying`; malformed
  inputs → `:invalid`; ledger record/reject shapes and the
  0001-world invariance; diff add/modify/delete detection,
  symlink flagging, and refusal cases). Every identity is
  `synth-*`; no network, no live credentials.
- `./scripts/check` on `feat/0006-patch-admission`:
  **278 tests, 2693 assertions, 0 failures, 0 errors**
  (Temurin 17.0.20, Clojure 1.12.0); all CLI gates green
  (0002–0005 replays, bundles, observations, gates), no
  network, all fixtures `synth-*`.
- Honest limits: admission is the pure verdict plus the
  ledger events; the supervisor loop that runs the pinned
  verification recipe and records `:patch/admitted` with the
  evidence digest lands with the T8 `run-task` wiring. The
  agent adapters that produce diffs in the loop are T7.

## Slice E evidence — T7 (2026-09-21)

- New explicit adapter `axiom.adapters.agent` (R9): the agent
  interface with `:agent/fake` and `:agent/process`
  implementations, distinguished by declared `:agent/kind`.
  The fake agent deterministically replays a fixture script of
  `:proposal` / `:adversarial` steps (scripted moves:
  `:scope-widen`, `:unauthorized-shell`, `:stale-token` —
  returned as data, never executed); the process agent spawns
  the worker as a bounded OS subprocess with argv from the
  admitted proposal only (never invented), the environment
  scrubbed of credential/token-shaped variables
  (`scrub-env`), the workdir confined to the worktree
  (validated via `resolve-path`), a wall-clock timeout with
  SIGKILL on expiry, and cancellation via SIGKILL.
  `start-agent` returns `{:agent/cancel!, :agent/wait!}` so the
  supervisor holds the kill handle while the worker runs;
  `run-agent` blocks via `wait!`. Stdout is parsed as EDN and
  schema-validated as a proposal before the supervisor reads
  it (`:invalid-output` otherwise); spawn failures are
  `:spawn-failed`.
- Supervisor-side budget enforcement: pure
  `axiom.execute/check-budgets` over (task, usage) —
  `:budget/max-cost`, `:budget/max-attempts`,
  `:budget/max-wall-seconds`; any exceeded budget yields the
  actionable blocker `:budget-exhausted` (the supervisor then
  records `:task/blocked` with the blocker named — the
  existing `:task/blocked` event carries `:task/blockers`);
  unconfigured budgets are unbounded; malformed usage is
  `:invalid`, never a silent pass.
- New tests: `agent_test` (7 deftests — fake script replay
  order and exhaustion; adversarial moves returned as data;
  process happy path with argv-only spawn and schema-valid
  output; runaway `sleep 30` SIGKILLed at a 500ms timeout;
  `cancel!` SIGKILLs a live worker and `wait!` reports
  `:cancelled`; non-EDN output → `:invalid-output`;
  `scrub-env` removes credential vars; budget within/exceeded/
  unbounded/malformed; and a guarded-loop integration where a
  fake adversarial unauthorized-shell proposal is denied by
  `evaluate-proposal` with `:unauthorized-capability`).
  Every identity is `synth-*`; the process adapter runs only
  local synthetic commands; no network, no live credentials.
- `./scripts/check` on `feat/0006-agent-adapters`:
  **284 tests, 2739 assertions, 0 failures, 0 errors**
  (Temurin 17.0.20, Clojure 1.12.0); all CLI gates green
  (0002–0005 replays, bundles, observations, gates), no
  network, all fixtures `synth-*`.
- Honest limits: the adapters are exercised directly and
  through the pure evaluator; the supervisor loop that drives
  them end-to-end (the T8 `run-task` wiring) is the next
  slice. The fake agent's scripted proposals are fixtures;
  the process agent never runs untrusted code in tests.

## Slice E2 evidence — T6 corrections (2026-09-21)

Focused correction PR for the T6 audit findings (PR #32 merged
green but the audit identified integration gaps):

1. **Symlink diff integration (fixed).** `diff-worktree`
   previously emitted symlink operations with `:diff/digest
   nil`, while `admit-patch`'s `diff-shape-ok?` requires a
   SHA-256 digest on every operation — an actual
   adapter-produced symlink became `:invalid/:malformed`
   instead of the required `:path-safety-violation`.
   `list-files` now records a symlink's digest as the SHA-256
   of its link target (via `readSymbolicLink`, which never
   follows the link). The shape check passes, and
   `patch-path-safe?` denies with `:path-safety-violation`.
   New end-to-end test: a real symlink through
   `diff-worktree` → `admit-patch` yields `:deny` /
   `:path-safety-violation`.

2. **Approved-policy gate (reviewed, no change).** R8's "gate
   evaluation under the approved policy" describes the
   admission function's nature — a pure gate with named
   reasons evaluated only when an approved policy is present
   — not a call into `axiom.gate` (which evaluates PR
   check-run gates, a different domain from spec 0005). The
   `:governance/policy-approved` event is itself a
   hash-chained ledger event; its content is referenced by
   `:governance/policy-id`. The current presence/shape check
   with `:no-approved-policy` denial satisfies the gate.

3. **Post-action verification (fixed).** New pure
   `axiom.execute/verify-patch`: binds the verification run
   result to the `:allow` verdict, requires the executed
   recipe to equal the pinned recipe byte-for-byte
   (`:recipe-mismatch` otherwise), and constructs the
   `:patch/evidence-digest` bound to the patch digest — the
   digest the `:patch/admitted` ledger event records. Nonzero
   exit is `:verification-failed`, never a silent pass. The
   recipe execution itself remains a T8 supervisor effect.

4. **Kernel deletion (fixed).** The class check no longer
   excludes `:delete` operations: deleting a
   kernel-namespace file (e.g. `src/axiom/ledger.clj`) under a
   `:task-class/standard` task now denies with
   `:self-modification-requires-promotion`; the same deletion
   under `:task-class/elevated` is allowed.

- `./scripts/check` on `feat/0006-t6-corrections`:
  **287 tests, 2758 assertions, 0 failures, 0 errors**
  (Temurin 17.0.20, Clojure 1.12.0); all CLI gates green,
  no network, all fixtures `synth-*`.
