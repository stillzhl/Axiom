# Tasks and acceptance gates

- [ ] T1: Implement the pure `axiom.execute` port: the proposal
  model (proposal/action/note shapes), `evaluate-proposal`
  (fencing-token check, lease-holder check, scope check,
  capability check, path-safety check, class check — in that
  order, whole-proposal deny with a named reason, no partial
  admission), and the task lifecycle state machine
  (accepted → running → completed/blocked/cancelled). No I/O;
  synthetic fixtures only. Acceptance: out-of-scope proposals
  are denied with named reasons before any action executes;
  instruction-like prompt text in `:proposal/note` or action
  fields never widens scope; the property "adding unsupported
  agent claims cannot turn deny/defer into allow" holds over
  the proposal evaluator (machine-tested).
- [ ] T2: Implement context projection: pure
  `project-context(task, policy, ledger-state, budget)`.
  Mandatory blockers are never droppable — a budget too small
  to hold them fails with `:context-budget-too-small`;
  projected obligation states are copied, never recomputed.
  Acceptance: projection under a tiny budget preserves every
  blocking obligation; obligation states are byte-identical
  before and after projection.
- [ ] T3: Ledger integration for leases and fencing:
  `:lease/acquired`, `:lease/renewed`, `:lease/released`,
  `:lease/expired`, `:lease/revoked` through the 0002 append
  path (transactional single-holder invariant), the current-
  lease projection, and fencing-token validation on every
  worker-submitted record. Forward-only schema v5 migration;
  replay-equivalence holds. Acceptance: two concurrent acquire
  attempts yield exactly one lease; renewal with a wrong token
  is denied; any worker record with a superseded token is
  denied with `:stale-fencing-token`; stale workers cannot
  publish, admit patches, or move outbox intents.
- [ ] T4: Implement the action outbox (the 0002-deferred item):
  intent records with idempotency keys, supervisor-only
  transitions (`:intent-recorded → :executing → :executed |
  :failed`, plus `:uncertain`), and the pure
  `reconcile-outbox` over (outbox-state, provider-state).
  Acceptance: a simulated crash after external success
  reconciles to exactly-once execution; duplicate intent
  submissions are deduplicated on the idempotency key; an
  uncertain intent is resolved by provider query, never by
  blind re-execution (asserted against the fake provider
  double).
- [ ] T5: Capability dispatch and the isolated worktree adapter
  shape: the grant model on the task record, the pure
  `check-capability` predicate, the closed capability set
  (`:unknown-capability` rejected), and
  `axiom.adapters.worktree` (create/confine/destroy, pinned
  base identity, structural path confinement). Acceptance: a
  worker requesting `:capability/shell` without a grant is
  rejected and no process is spawned; ungranted file writes
  are refused; two tasks never share a worktree identity.
- [ ] T6: Patch admission and post-action verification:
  `admit-patch` over the worktree diff (proposal coverage,
  lease currency, path safety per the 0002/0003 rules, class
  check), the pinned verification recipe run (0003 runner
  shape), and evidence recording. Admission is a gate
  evaluation under the approved policy. Acceptance: a patch
  with a symlink escape is denied with
  `:path-safety-violation`; a patch with no covering proposal
  is denied with `:no-approved-proposal`; a kernel-namespace
  patch under a standard task is denied with
  `:self-modification-requires-promotion`; admitted patches
  carry their verification evidence digest.
- [ ] T7: Agent adapters: the `axiom.adapters.agent` interface
  with the `:fake` implementation (deterministic scripts from
  fixtures, including scripted adversarial moves: a
  scope-widening proposal, an unauthorized shell request, a
  stale-token resubmission) and the `:process` implementation
  (argv from admitted proposals only, scrubbed environment,
  workdir confined to the worktree, wall-clock timeout with
  SIGKILL, cancellation via SIGKILL, schema-validated output),
  plus supervisor-side budget enforcement (cost, attempts,
  wall time). Acceptance: the fake adapter completes the
  synthetic docs task through the guarded loop; the process
  adapter kills a runaway worker at the timeout and records
  the cancellation; an over-budget run stops with
  `:budget-exhausted` and the task is `:task/blocked` with the
  blocker named.
- [ ] T8: CLI and verification: `run-task --task TASK-EDN
  --adapter fake|process` driving one synthetic task
  end-to-end through the guarded loop (admission, projection,
  lease, proposal loop, patch admission, verification, and —
  only with a recorded `:governance/publication-authorized`
  event — outbox PR publication through the fake provider);
  exit contract 0/4/5 matching 0002–0005; full
  `./scripts/check` green; this spec's `verification.md`
  updated with exact commands, test counts and evidence;
  acceptance-gate review against `acceptance.md`. No M5
  milestone or v1 acceptance is claimed from this spec alone;
  milestone gates belong to the design's M5 gate review.
