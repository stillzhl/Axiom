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
