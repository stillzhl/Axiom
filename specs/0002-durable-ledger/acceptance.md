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

## Implementation acceptance (future slice — not yet run)

Spec 0002 becomes **Verified** only when every task T1–T8 is complete and:

- `./scripts/check` is green on Temurin 17.0.20 / Clojure 1.12.0 with the
  extended ledger gates, and the same checks pass in remote CI from a clean
  checkout;
- replay of a committed ledger prefix reproduces recorded decisions
  byte-identically (decision identity digests equal the 0001 `evaluate`
  results for the same inputs);
- snapshot restore + replay equals full replay (replay equivalence);
- bundle replay equals ledger replay (identical decision digests);
- duplicate event IDs and duplicate deduplication keys are rejected
  deterministically; interrupted appends leave no phantom events;
- schema migration preserves replay equivalence and never rewrites stored
  payloads; newer-schema-than-code fails operationally;
- the 0001 exit contract is unchanged: evaluate allow 0 / missing 3 /
  stale 3 / failed 2; status/next/explain exit 0; replay/export-bundle
  exit 0 on valid reports, 4 on invalid input, 5 on operational failure;
- verification.md records exact commands, results and remaining limits.

No milestone acceptance (M2 or Axiom v1) is claimed from this spec alone;
milestone gates belong to the design's M2 gate review.
