# Acceptance gates for spec 0006

This file records the acceptance criteria that make spec 0006 "Accepted"
(spec-complete) versus "Verified" (implementation-complete). The gates were
recorded before any implementation, per the repo's contributor instructions.

## Spec acceptance (this slice — met 2026-09-20)

- [x] requirements.md, design.md, tasks.md, verification.md and this
  acceptance.md are written and internally consistent.
- [x] Scope decision recorded: 0006 is the M5 supervised-execution
  slice (context projection, structured proposal protocol,
  fake-agent adapter, isolated worktrees/workers, capability
  dispatch, action outbox, leases and fencing, patch admission
  and post-action verification, one real agent adapter with
  timeout/cancellation/output validation/budgets, PR publication
  only when separately authorized), plus the items earlier specs
  deferred to M5+: the 0002 `leases`/`action_outbox` items and the
  0005 self-hosting policy promotion gates. Automatic merge is out
  of scope entirely — an allow decision is not a merge
  instruction. Vendor-specific coding-agent API integrations are
  deferred; the process adapter is the one real adapter.
- [x] Authorization model explicit and extended: the 0005 roles
  (authorizer/owner, evaluator/trusted, candidate/untrusted) carry
  over, plus `:worker/untrusted`, which authorizes nothing; a
  candidate version cannot govern its own policy or evaluator
  upgrade — self-modifying tasks verify under the pinned previous
  evaluator release, candidate-authored tests cannot be the sole
  acceptance basis, and promotion requires the authorizer's
  `:governance/promotion-accepted` event. Untrusted worker output
  is validated data, never evaluated as policy and never executed
  as code.
- [x] Core-purity and adapter-boundary rules stated: the new pure
  `axiom.execute` port carries proposal evaluation, context
  projection, lease/fencing logic, outbox reconciliation, patch
  admission and capability checks; side effects live only in
  `axiom.adapters.worktree`, `axiom.adapters.agent` (fake and
  process), the 0005 checks adapter, and the new
  `axiom.adapters.pr` (PR creation only — no merge code path).
  The 0001–0005 decision bytes and exit contracts are explicitly
  preserved; ledger schema changes are additive only (schema v5,
  forward-only).
- [x] Consumer-agnostic rule restated: all fixtures synthetic
  (`synth-*`); nothing HomeKV-specific; no live credentials or
  real repository identities in fixtures or evidence.
- [x] Honest limits recorded: the fake agent proves the guarded
  loop's shape, not real-agent reliability; prompt-injection
  resistance is tested against synthetic adversaries only; the
  process adapter is not a sandboxing proof; test evidence will
  be bounded synthetic observation, not formal proof and not a
  production trust attestation; the first self-driven task must
  be a bounded documentation or adapter change outside the
  trust-critical kernel.
- [x] `./scripts/check` passes on this docs-only change (no implementation
  claims made from it).

Spec status after this slice: **Accepted** — requirements, design, tasks and
acceptance gates are recorded. Verification is pending implementation.

## Implementation acceptance (pending)

- [ ] `./scripts/check` is green on Temurin 17.0.20 / Clojure 1.12.0
  with the 0006 gates; the 0006 probes drive a synthetic task
  end-to-end through the guarded loop (fake adapter), exercise
  the process adapter's timeout/cancellation/budgets, and cover
  the design §13 execution cases (timeout, cancellation, outbox
  uncertainty, stale fencing token, unauthorized tool request,
  secret isolation); the 0001–0005 exit contracts are unchanged;
  `git diff --check` clean. No live network access in the check
  gates.
- [ ] The design's M5 gate conditions hold in tests: two runs
  cannot mutate the same leased task concurrently; stale workers
  cannot publish; unauthorized shell/tool requests are rejected;
  a simulated crash after external success reconciles safely to
  exactly-once; prompt text cannot override policy; the complete
  synthetic task completes through the guarded loop.
- [ ] Lease events, outbox intents, proposals, patch admissions
  and verification evidence append through the 0002 path;
  snapshots remain replay-equivalent; replay of a prefix
  reproduces the task lifecycle verbatim, including which
  evaluator release produced each decision.
- [ ] `run-task` publishes a PR only with a recorded
  `:governance/publication-authorized` event; without it the
  patch stays local. No merge code path exists anywhere.
- [ ] A self-modifying task verifies under the pinned previous
  evaluator release; promotion requires the authorizer's
  `:governance/promotion-accepted`; candidate-authored tests are
  never the sole acceptance basis.
- [ ] verification.md records exact commands, results and remaining
  limits.

Spec status: **Accepted** (2026-09-20) — implementation gates
pending. No M5 milestone or v1 acceptance is claimed from this spec
alone; milestone gates belong to the design's M5 gate review.
