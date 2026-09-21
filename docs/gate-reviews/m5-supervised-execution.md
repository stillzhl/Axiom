# M5 gate review — Supervised agent execution

Reviewed: 2026-09-21. Authority: the owner's standing Axiom autopilot
instruction; this is a docs-only milestone audit, not an independent
security review. Source: the proposed design plan, "M5 — Supervised
agent execution" (Implement list + Gate). Candidate evidence: spec
`0006-supervised-execution`, **Verified** (implementation-complete) on main
`c040d631` (slices PR #28–#37).

Rule: this review audits the spec against the design's M5 criteria. It
does not edit any spec's Verified claims and does not claim anything
beyond M5 (no v1, no M6 acceptance).

## Implement list audit

- **Context projection, structured proposal protocol, and fake-agent
  adapter** — satisfied. Spec 0006 R1–R3: `axiom.execute/project-context`
  (pure) projects the task scope; proposals are data
  (`:proposal/actions` with `:action/kind`, capability, and
  content digest); the fake adapter (`axiom.adapters.agent` `:fake`
  kind) replays a deterministic script. Evidence:
  `src/axiom/execute.clj` (`project-context`, `evaluate-proposal`);
  `src/axiom/adapters/agent.clj`; `axiom.execute-test`,
  `axiom.agent-test`.
- **Isolated worktrees/workers, capability dispatch, action outbox,
  leases, and fencing** — satisfied. Spec 0006 R4–R7: worktrees are
  isolated temp dirs (`axiom.adapters.worktree`); capability dispatch
  checks `:task/capabilities` against the action's required
  capability; the action outbox (`axiom.execute` pure +
  `axiom.ledger` events) records intents with `:outbox/reason` and
  `:outbox/detail`; leases carry fencing tokens
  (`:lease/token`) and `evaluate-proposal`/`admit-patch` reject
  stale tokens. Evidence: `src/axiom/adapters/worktree.clj`;
  `src/axiom/execute.clj` (`acquire-lease`, `check-fencing`,
  outbox reconciliation); `axiom.lease-test`, `axiom.outbox-test`.
- **Patch admission and post-action verification** — satisfied. Spec
  0006 R8–R9: `admit-patch` (pure) checks the diff against admitted
  proposals, scope, and policy; `verify-patch` runs the pinned
  recipe and binds evidence to the patch digest. Evidence:
  `src/axiom/execute.clj` (`admit-patch`, `verify-patch`);
  `axiom.patch-test`.
- **One real agent adapter with timeout, cancellation, output
  validation, and cost/attempt budgets** — satisfied. Spec 0006 R10:
  the `:process` adapter (`axiom.adapters.agent`) spawns a
  subprocess with worktree confinement, environment scrubbing,
  timeout SIGKILL, async cancellation, stdout parsing, and schema
  validation; `check-budgets` (pure, supervisor-side) enforces
  cost/attempt limits. Evidence: `src/axiom/adapters/agent.clj`;
  `axiom.agent-test` (timeout, cancellation, budgets).
- **PR publication only when separately authorized; automatic merge
  remains a distinct optional capability** — satisfied. Spec 0006
  R12: `axiom.adapters.pr` creates PRs (fake adapter records a
  `synth-pr-*` reference); `supervisor/run-task` publishes only
  when the task carries a recorded
  `:governance/publication-authorized` event; no merge code path
  exists anywhere. Evidence: `src/axiom/adapters/pr.clj`;
  `src/axiom/supervisor.clj`; `axiom.adapters.pr-test`,
  `axiom.supervisor-test`.

## Gate audit

The design's M5 gate: "two runs cannot mutate the same leased task
concurrently; stale workers cannot publish; unauthorized shell/tool
requests are rejected; crashes after external success reconcile
safely; prompt text cannot override policy; the complete synthetic
task completes through the guarded loop."

- **Two runs cannot mutate the same leased task concurrently** —
  satisfied. Fencing tokens bind every proposal and patch to the
  lease holder; a second run with a different token is rejected.
  Evidence: `axiom.execute/check-fencing`; `axiom.lease-test`.
- **Stale workers cannot publish** — satisfied. Publication requires
  the current fencing token; stale tokens are rejected before any
  external effect. Evidence: `axiom.execute/evaluate-proposal`
  fencing check; `axiom.supervisor-test`.
- **Unauthorized shell/tool requests are rejected** — satisfied.
  Capability dispatch denies actions whose required capability is
  not in `:task/capabilities`; out-of-scope paths deny with
  `:out-of-scope`. Evidence: `axiom.execute/evaluate-proposal`;
  `axiom.supervisor-test` (`:prompt-scope-escape` → `:out-of-scope`).
- **Crashes after external success reconcile safely** — satisfied.
  The action outbox records intents before external effects; on
  replay, completed intents are not re-executed (exactly-once).
  Evidence: `axiom.execute` outbox reconciliation;
  `axiom.outbox-test` (simulated crash → safe reconcile).
- **Prompt text cannot override policy** — satisfied. The fake
  agent's adversarial `:prompt-scope-escape` move (instruction-shaped
  prompt content attempting scope widening) is denied by
  `evaluate-proposal` with `:out-of-scope` before any action
  executes. Evidence: `axiom.supervisor-test`
  (`run-task-blocks-on-prompt-scope-escape`).
- **The complete synthetic task completes through the guarded loop**
  — satisfied. `supervisor/run-task` drives a synthetic docs task
  (fake adapter) through admission → lease → proposal → patch
  admission → verification → ledger → completion, with patch
  digest and evidence digest in the report. Evidence:
  `axiom.supervisor-test`
  (`run-task-completes-synthetic-docs-task`); **297 tests, 2792
  assertions, 0 failures, 0 errors** on `c040d631`.

## Verdict

**M5 Complete** — every design M5 implement item and every gate
condition is satisfied by Verified spec 0006, with evidence
pointers above. Caveats (from the spec's honest limits): the fake
agent proves the guarded loop's shape, not real-agent reliability;
prompt-injection resistance is tested against synthetic adversaries
only; the process adapter is not a sandboxing proof; the first
self-driven task must be a bounded docs/adapter change outside the
trust-critical kernel. No v1 or M6 acceptance is claimed.

Roadmap: M5 row → Complete (2026-09-21).
