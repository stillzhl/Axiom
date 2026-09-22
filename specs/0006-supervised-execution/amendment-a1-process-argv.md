# Amendment A1 — Drive `:process` agents through `run-task` (2026-09-22)

Status: Draft — requirements, design, tasks and acceptance gates
recorded before implementation, per the repo's contributor
instructions. Authority: the repository owner's standing
self-hosting direction ("Continue on axiom until it can
self-hosting", 2026-09-21) and the autopilot rails (one
focused branch/PR per bounded slice; merge only when green;
never push to main).

## Why an amendment to 0006, not a new spec 0007

Spec 0006 R9 normatively requires "one real agent adapter" as a
bounded OS subprocess, T7 implemented the `:process` adapter,
and T8's acceptance gate required the process path — but the
verification record's honest limits explicitly note "the full
`run-task --adapter process` loop is not covered by an automated
test," and the 2026-09-21 self-hosting run proved empirically
that `supervisor/run-task` never passes `:agent/argv`, so the CLI
path blocks as `:malformed` on the first step. The gap is
integration plumbing for already-specified behavior: no new
adapter kinds, no new trust boundary, no new milestone scope, no
merge path. The design's M6 is the consumer pilot — a different
scope entirely — so a 0007 number would mislabel this work.
Like the T6 corrections (PR #32), this amendment completes
0006's stated requirements and is recorded in 0006's own
verification/acceptance docs; it does not reopen the M5
milestone gate review, and no M0/v1 acceptance is claimed from
it.

## Requirements

- AR1: `:agent/argv` task schema and validation. A task that
  selects the `:process` adapter must carry `:agent/argv`: a
  non-empty vector of non-blank strings whose first element
  (the executable) is an absolute path. Missing, empty,
  non-vector, blank-element, or relative-executable argv is
  `:invalid` at task admission (`run-task` input validation →
  exit 4), never a silent default — the adapter never invents
  argv. For the `:fake` adapter `:agent/argv` is ignored
  (script-driven), but when present it is still validated.
  The validated argv is recorded on the `:task/accepted` event
  (new optional field, additive) and in the run report.
  Testable: every malformed shape → exit 4, and no subprocess
  is spawned (assertable: the adapter is never constructed).
- AR2: Supervisor argv plumbing. `supervisor/run-task` passes
  the task's validated `:agent/argv` to the process agent's
  `run-agent` call. The fake path is unchanged. Testable: a
  process task's worker is spawned with exactly the task's
  argv (asserted on the spawned command line).
- AR3: Process-path worktree effects. For the `:process`
  adapter the supervisor does NOT run the synthetic
  `apply-actions!` (the worker wrote the files itself;
  re-writing would clobber real effects). Instead, for each
  admitted `:action/write-file`, the supervisor cross-checks
  the agent-claimed `:action/content-digest` byte-for-byte
  against the worktree file; a missing file or a digest
  mismatch blocks the run with `:content-digest-mismatch`.
  The fake path keeps the synthetic apply. Testable: a
  worker that prints a proposal claiming a digest different
  from the bytes it wrote is blocked with the named reason.
- AR4: Real pinned-recipe verification. The supervisor
  executes the task's `:task/pinned-recipe` for real in the
  worktree via `axiom.adapters.runner/run!` with an explicit
  one-command registry built from the validated recipe
  (absolute executable, fixed-string args, worktree workdir,
  timeout from `:task/recipe-timeout-seconds` defaulting to
  120s, 1MB output caps). The recipe runs byte-for-byte —
  the pure `execute/verify-patch` `:recipe-mismatch` check is
  unchanged. Nonzero exit → `:verification-failed`; runner
  timeout → `:verification-timed-out`; output cap →
  `:verification-output-capped`; cancellation →
  `:verification-cancelled`; spawn failure → exit 5. All
  named, all `:task/blocked` (exit 0 — the run is valid, the
  task is not), never silent. The evidence digest still
  binds patch digest + recipe + output. Testable: a recipe
  that exits 1 blocks with `:verification-failed`; a recipe
  that sleeps past the timeout blocks with
  `:verification-timed-out`.
- AR5: Budget enforcement in the loop. The supervisor tracks
  usage — `:budget/attempts` (agent steps taken),
  `:budget/wall-seconds` (loop wall time),
  `:budget/cost` (agent-step wall milliseconds) — and checks
  the pure `execute/check-budgets` before each agent step;
  `:budget-exhausted` records `:task/blocked` with the
  blocker named and stops the run (exit 0, per the 0006 CLI
  contract). Unconfigured budgets remain unbounded.
  Testable: a task with `:budget/max-attempts 1` and a
  two-step script stops after the first step with
  `:budget-exhausted`.
- AR6: Governance event kinds for 0006 (the schema-path fix).
  The 0006 design's ledger-integration section already lists
  `:governance/publication-authorized` and
  `:governance/promotion-accepted` as 0002 payload kinds, but
  they were never added to `ledger/governance-event-kinds` —
  recording `:governance/publication-authorized` failed
  validation on 2026-09-21 ("Unknown governance event
  kind"). This amendment adds both kinds with strict
  per-kind validation in `validate-governance-event!`:
  `:governance/publication-authorized` requires
  `:event/kind`, `:task/id` (non-blank string) and an
  authorizer identity (`:governance/authorizer` or
  `:governance/approver`); optional
  `:governance/policy-id` (non-blank string) and optional
  `:patch/digest` (sha256-shaped — names the exact patch
  when the authorizer issues post-admission, per R10's
  strong form); `:governance/promotion-accepted` requires
  `:event/kind`, `:task/id` and an authorizer identity,
  with optional `:patch/digest` and
  `:governance/evidence-digest`. Unknown fields are
  `:invalid`. Both are recordable through the existing
  `ledger/record-governance` (docstring updated) and are
  additive — they contribute zero 0001 world events.
  Testable: missing authorizer, unknown field, or a
  malformed digest → `:invalid`, never writable.
- AR7: Ledger-backed publication gate. The authorizer's
  event still arrives as task data (`:task/publication-
  authorized` — the authorizer is out-of-band; the
  supervisor never invents authorizations), but the
  supervisor now validates and records it into the ledger
  via `record-governance` at admission (malformed →
  `:invalid`, exit 4), and the publication check reads the
  ledger-recorded event. `pr/publication-authorized?`
  gains a 3-arity `[event task-id patch-digest]`: the task
  id must match, and when the event names a `:patch/digest`
  it must equal the admitted patch digest, else
  `:publication-digest-mismatch` and the patch stays local.
  The 2-arity is preserved for the synthetic path.
  Decision recorded: task-data carriage remains the *input*
  mechanism; the schema path is fixed so the authorization
  is ledger-native thereafter. A future spec may require
  the digest-bound (post-admission) form; until then the
  pre-run form authorizes whatever patch the loop admits,
  and the authorizer's manual digest check at real-PR
  publication is the strong form.
- AR8: Process adapter hardening. `adapters.agent`
  `start-process` tightens its input contract: argv
  elements must be non-blank strings (a blank element is
  `:malformed`, never spawned); `:agent/env` when present
  must be a map with string keys and string values.
  Named reasons (`:malformed`, `:spawn-failed`,
  `:timed-out`, `:cancelled`, `:invalid-output`,
  `:workdir-escape`) are unchanged. Credential scrubbing
  and worktree confinement are unchanged.
- AR9: No new trust boundary. The worker remains untrusted;
  argv is task data set by the task author (the governed
  party), never by the worker; the adapter never invents
  argv. No merge code path. No live-network tests. The
  0001–0005 decision bytes, exit contracts, and ledger
  schema semantics are unchanged beyond the additive
  fields/kinds above.

## Explicit boundaries

No new adapter kinds; no vendor agent integrations; no
automatic merge; no consumer-specific content (all fixtures
`synth-*`); no live-network tests; the process adapter is
still not a sandboxing proof; prompt-escape resistance is
still tested against synthetic adversaries only. The
supervisor's real recipe execution runs the task author's
pinned recipe in the worktree with the runner's bounds —
recipes are task data, reviewed by whoever authors the task,
not by the worker. The genuine self-driven run that
follows this amendment is a bounded docs change outside the
trust-critical kernel, per the 0006 first-task rule.

## Design

- `axiom.ledger`: `governance-event-kinds` gains the two
  0006 kinds; `validate-governance-event!` gains their
  per-kind cases (AR6); `validate-task-event!`
  (`:task/accepted`) gains the optional `:agent/argv` field
  validated by the shared argv rule; `record-governance`
  docstring updated. New pure predicate
  `valid-agent-argv?` (vector, non-empty, all non-blank
  strings, first absolute) — the single definition of the
  argv shape, used by both the ledger and the supervisor.
- `axiom.supervisor` (`run-loop`): input validation
  (AR1 — process requires argv; `:task/agent-timeout-ms`
  positive-integer-or-default 30000;
  `:task/recipe-timeout-seconds` 1..3600-or-default 120;
  `:task/seed-dir` absolute-existing-directory-or-absent,
  copied into the worktree before `:task/base-files`
  seeding); the proposal loop passes
  `{:agent/argv argv}` to `run-agent` for `:process`
  (AR2); process path skips `apply-actions!` and
  cross-checks claimed content digests against worktree
  bytes (AR3); budget usage tracked and
  `execute/check-budgets` consulted before each agent
  step (AR5); governance event recorded at admission via
  `record-governance` (AR7); post-`:allow` verification
  builds the one-command runner registry from the pinned
  recipe and runs it for real in the worktree, mapping
  runner outcomes to named blockers (AR4); publication
  uses the 3-arity authorization check against the
  ledger-recorded event (AR7). The fake path is
  behaviorally unchanged.
- `axiom.adapters.agent`: tightened `start-process`
  request validation (AR8); spawn/timeout/cancel/scrub
  behavior unchanged.
- `axiom.adapters.pr`: `publication-authorized?` 3-arity
  (AR7); 2-arity preserved.
- `axiom.execute`: unchanged — the pure core is reused
  as-is (`evaluate-proposal`, `admit-patch`,
  `verify-patch`, `check-budgets`).
- CLI: unchanged — `run-task --task TASK-EDN --adapter
  process` already threads the adapter through; the task
  EDN carries the new fields.

## Tasks

- [ ] A1-S1: This amendment doc → docs PR → Accepted.
- [ ] A1-S2: Ledger: the two governance kinds + validation,
  `record-governance` docstring, `:task/accepted`
  `:agent/argv`, `valid-agent-argv?`, tests
  (`ledger_0006_test` additions: valid records for both
  kinds; missing authorizer / unknown field / bad digest
  / bad argv shapes → `:invalid`; governance payloads
  contribute zero 0001 world events). → PR.
- [ ] A1-S3: Agent adapter hardening: non-blank argv
  elements, env map-of-strings validation, tests
  (`agent_test` additions). → PR.
- [ ] A1-S4: Supervisor: argv plumbing, `:task/seed-dir`
  worktree population, process-path digest cross-check
  (no synthetic apply for `:process`), budget tracking +
  `check-budgets` in the loop, agent-timeout task field,
  tests (`supervisor_test` additions: malformed/missing
  argv → exit 4 with no spawn; process happy path with a
  real script through the loop to `:task/completed`;
  digest mismatch → `:content-digest-mismatch`;
  `:budget/max-attempts 1` two-step script →
  `:budget-exhausted`). → PR.
- [ ] A1-S5: Supervisor: governance recording at
  admission + ledger-backed publication check
  (`pr` 3-arity), real recipe execution via the
  one-command runner registry with named outcome
  mapping, tests (governance malformed → exit 4;
  unauthorized publication → patch stays local;
  digest mismatch → `:publication-digest-mismatch`;
  recipe exit 1 → `:verification-failed`; recipe
  timeout → `:verification-timed-out`). → PR.
- [ ] A1-S6: Verification evidence: update this doc's
  evidence section with exact commands, test counts and
  digests; run the genuine bounded self-driven change
  through the real `axiom run-task --adapter process`
  CLI (no driver workaround); record task id, argv,
  ledger envelopes with digests, patch digest, recipe
  result, and the opened PR's CI. → PR (docs-only)
  recording the run; the run's own PR is opened
  separately and NOT merged by the worker.

## Acceptance gates

- [ ] `./scripts/check` green on Temurin 17.0.20 /
  Clojure 1.12.0; 0001–0005 exit contracts unchanged;
  `git diff --check` clean; no live network in tests.
- [ ] Malformed/missing `:agent/argv` on a process task →
  exit 4, `:invalid`, no subprocess spawned.
- [ ] `run-task --adapter process` drives a real worker
  end-to-end through the guarded loop to
  `:task/completed` with a hash-chained ledger, in
  tests and in the genuine run.
- [ ] Claimed content-digest mismatch →
  `:content-digest-mismatch`; the run is blocked and the
  patch is not admitted.
- [ ] Budget exhaustion → `:budget-exhausted` recorded as
  `:task/blocked` with the blocker named; the run stops
  (exit 0).
- [ ] `:governance/publication-authorized` and
  `:governance/promotion-accepted` are recordable through
  `ledger/record-governance`; missing authorizer, unknown
  field, or malformed digest → `:invalid`, never
  writable.
- [ ] Malformed `:task/publication-authorized` at
  admission → `:invalid`, exit 4; unauthorized
  publication → patch stays local; bound digest
  mismatch → `:publication-digest-mismatch`.
- [ ] The pinned recipe is executed for real in the
  worktree: recipe exit 1 → `:verification-failed`;
  recipe timeout → `:verification-timed-out`.
- [ ] No automatic merge: the supervised machinery opens
  PRs and verifies CI only; every merge decision stays
  with the main agent.
- [ ] Amendment status moves to Accepted when this doc
  lands on `main` through a green docs PR.

## Evidence

(A1-S6) To be recorded here after the genuine bounded
self-driven change runs through the real `axiom
run-task --adapter process` CLI: exact command, task
id, `:agent/argv`, ledger envelopes with digests, patch
digest, recipe verification result, and the opened PR's
CI evidence.
...[truncated 1031 chars]