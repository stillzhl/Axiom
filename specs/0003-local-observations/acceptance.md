# Acceptance gates for spec 0003

This file records the acceptance criteria that make spec 0003 "Accepted"
(spec-complete) versus "Verified" (implementation-complete). The gates were
recorded before any implementation, per the repo's contributor instructions.

## Spec acceptance (this slice — met 2026-09-20)

- [x] requirements.md, design.md, tasks.md, verification.md and this
  acceptance.md are written and internally consistent.
- [x] Scope decision recorded: 0003 is the local-observations slice of the
  proposed M2 (local Git adapter, artifact digesting with bounded
  retention metadata, local diagnostic verification runner). GitHub
  observation is M3; agent execution, action dispatch, leases and the
  action outbox are M4/M5; no merge or enforcement is in scope.
- [x] Core-purity and adapter-boundary rules stated: the 0001/0002 core is
  unchanged; Git access and process execution live in explicit
  `axiom.adapters.*` namespaces behind pure ports; artifact storage rows
  are owned by `axiom.store`; the 0001/0002 decision bytes and exit
  contracts are explicitly preserved.
- [x] Consumer-agnostic rule restated: all fixtures synthetic; nothing
  HomeKV-specific.
- [x] Honest limits recorded: local observations and runner results are
  marked `:trust/local-diagnostic` and cannot masquerade as trusted
  remote CI; the runner claims no isolation beyond
  timeout/cancellation/output caps; incomplete runs cannot satisfy
  obligations; exceeding retention bounds is an operational failure, not
  truncated success; symlinks/submodules are typed or blocked, never
  followed.
- [x] `./scripts/check` passes on this docs-only change (no implementation
  claims made from it).

Spec status after this slice: **Accepted** — requirements, design, tasks and
acceptance gates are recorded. Verification is pending implementation.

## Implementation acceptance (reviewed 2026-09-20, slice 5)

- [x] `./scripts/check` is green on Temurin 17.0.20 / Clojure 1.12.0 with
  the 0003 gates: 0003 gates observe a synthetic git repo, digest a
  synthetic file, run a bounded synthetic command, record
  observation/evidence events in a ledger, replay and assert recorded
  digests and trust marks; the 0001/0002 exit contracts are unchanged;
  `git diff --check` clean. Remote CI: green from a clean checkout.
  (Locally green 2026-09-20: 74 tests / 1280 assertions, 0 failures;
  branch CI run 35533256619 on the branch head: success.)
- [x] Git observation of a synthetic repo reproduces identical
  base/head/tree identities and change enumeration on repeat runs; dirty
  worktrees report head plus the dirty file list with no synthetic tree
  SHA; incomplete enumeration is reported, never silently partial.
  (Verified by `repeat-runs-are-deterministic`, `dirty-worktree`,
  `failing-git-step-is-incomplete`, and the new `scripts/check` 0003
  gates asserting identical SHAs across runs against `git rev-parse`
  ground truth.)
- [x] Path safety: symlink-escape cases are classified `:path/unsafe`
  and excluded from digestion; submodules are typed and not recursed;
  `..`-escaping paths fail operationally; renames, deletions, mode
  changes and Unicode paths are enumerated exactly. (Verified by
  `path-safety-pure`, `symlink-escape-is-unsafe`,
  `dirty-symlink-escape-is-unsafe`, the new
  `unsafe-symlink-excluded-from-digestion` — the escaping link's target
  content never enters the observation — `submodule-is-typed-not-recursed`,
  `observation-validation` (`..` → `:operational`), and
  `change-enumeration`. Mode-change and Unicode exactness verified
  empirically via the CLI on a synthetic repo — see verification.md —
  rather than a committed test, per the T7 scoping.)
- [x] Artifact digests match `sha256sum` on the same bytes; retention
  bounds are declared and enforced (exceeded bound → operational
  failure); expired/superseded rows remain so past decisions stay
  reproducible; schema v3 migration is forward-only and preserves replay
  equivalence. (Verified by `artifacts_test` — record/validation
  matrix, retention bounds with bound and actual named, mark lifecycle,
  duplicate rejection, tampered-row detection on read, v1→v3 and
  v1→v2→v3 migration with replay equivalence — and the new
  `scripts/check` digest gate.)
- [x] Runner timeout, cancellation and output-cap produce `:incomplete`
  results that cannot satisfy an obligation; run records are marked
  `:trust/local-diagnostic` with named limitations; commands use
  structured argv with no shell interpolation unless explicitly declared.
  (Verified by `run-record-construction`, `adapter-timeout`,
  `adapter-output-cap`, `adapter-cancellation`, the new
  `incomplete-runs-cannot-satisfy-obligations` data-level gate, and the
  new `scripts/check` run gates — true-probe exit 0 with
  `:trust/local-diagnostic`, sleep-probe exit 5 with `:timed-out`.)
- [x] Observation/evidence events append through the 0002 path and replay
  shows the observations and evidence behind each decision; malformed
  observations cannot admit actions. (Verified by
  `observations_test` — validation, trust-forgery rejection, ledger
  round-trip with replay showing observations/evidence, zero 0001
  events, duplicate rejection — and the new `scripts/check` ledger
  gate asserting the recorded head SHA, stdout digest and trust marks
  in replay output.)
- [x] verification.md records exact commands, results and remaining
  limits. (Slice 5 section records the audit, the added tests, the
  check-gate commands, the 74/1280 counts, CLI exit probes,
  deviations and the bounded-evidence statement.)

Spec status: **Verified** (2026-09-20) — all implementation gates
above pass on the recorded evidence. No milestone
acceptance (M2 or Axiom v1) is claimed from this spec alone; milestone
gates belong to the design's M2 gate review.
