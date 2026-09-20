# Acceptance gates for spec 0002

This file records the acceptance criteria that make spec 0002 "Accepted"
(spec-complete) versus "Verified" (implementation-complete). The gates were
recorded before any implementation, per the repo's contributor instructions.

## Spec acceptance (this slice — met 2026-09-20)

- [x] requirements.md, design.md, tasks.md, verification.md and this
  acceptance.md are written and internally consistent.
- [x] Scope decision recorded: 0002 is the durable-ledger slice of the
  proposed M2 (event store, snapshots, replay, export bundles); the local
  Git adapter, artifact retention and the diagnostic runner are explicitly
  deferred to later specs; leases and the action outbox belong to M4+.
- [x] Core-purity and adapter-boundary rules stated: the 0001 pure core is
  unchanged; SQLite lives in an explicit adapter namespace behind a pure
  port; the 0001 decision bytes and exit contract are explicitly preserved.
- [x] Consumer-agnostic rule restated: all fixtures synthetic; nothing
  HomeKV-specific.
- [x] Honest limits recorded: hash chain is tamper-evident, not
  tamper-proof; single-process ledger; crash testing covers the append
  protocol, not SQLite internals; test evidence is not proof.
- [x] `./scripts/check` passes on this docs-only change (no implementation
  claims made from it).

Spec status after this slice: **Accepted** — requirements, design, tasks and
acceptance gates are recorded. Verification is pending implementation.

## Implementation acceptance (run 2026-09-20)

- [x] `./scripts/check` is green on Temurin 17.0.20 / Clojure 1.12.0 with
  the extended ledger gates: 31 tests, 803 assertions, 0 failures/errors;
  `git diff --check` clean. Remote CI: green on PR #5 from a clean
  checkout (GitHub Actions run 35529057473, `offline-kernel` workflow,
  conclusion success).
- [x] Replay of a committed ledger prefix reproduces recorded decisions
  byte-identically: `replay-report` recomputes each decision and reports
  `:reproduced? true`; decision IDs equal the 0001 `evaluate` digests for
  the same inputs (unit tests + `scripts/check` gate).
- [x] Snapshot restore + replay equals full replay (world digests
  identical), including a generated 14-envelope stream snapshotted at a
  boundary; incompatible snapshots are ignored and rebuilt from events.
- [x] Bundle replay equals ledger replay: `scripts/check` diffs decision
  IDs from both replays — identical.
- [x] Duplicate event IDs and duplicate dedup keys rejected
  deterministically (`:duplicate` with reason); interrupted appends leave
  no phantom events; stale `prev_hash` rejected.
- [x] Schema migration v1→v2 preserves replay equivalence and never
  rewrites stored payloads (payload digests identical); newer-schema-than-code
  fails operationally (exit 5).
- [x] The 0001 exit contract is unchanged (evaluate allow 0 / missing 3 /
  stale 3 / failed 2; validate/status/next/explain exit 0); replay and
  export-bundle exit 0 on valid reports, 4 on invalid input, 5 on
  operational failure.
- [x] verification.md records exact commands, results and remaining limits.

Spec status: **Verified** — the 0002 durable-ledger slice is implemented
and its acceptance gates pass locally and in remote CI (PR #5, run
35529057473).

No milestone acceptance (M2 or Axiom v1) is claimed from this spec alone;
milestone gates belong to the design's M2 gate review. The remaining M2
items (local Git adapter, artifact digesting/retention, diagnostic
verification runner) are deferred to `0003+`.
