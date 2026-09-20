# Verification record

State: implementation complete 2026-09-20; spec 0002 Verified (this spec
only — not the broader M2 milestone or Axiom v1). Local `./scripts/check`:
31 tests, 803 assertions, 0 failures. Remote CI: green on PR #5
(run 35529057473).

## Implementation evidence — 2026-09-20

Branch `feat/0002-ledger-implementation`, PR #5. Local `./scripts/check`
(Temurin 17.0.20, Clojure 1.12.0): **31 tests, 803 assertions, 0 failures,
0 errors.** Remote CI (GitHub Actions) run: green on the PR branch; clean
checkout, same checks.

New namespaces:

- `axiom.ledger` (pure port): strict envelope validation, scenario/event
  record construction with hash chaining, contiguous-sequence and chain
  verification, world replay through the 0001 reducer, snapshot
  build/verify/restore, replay reports with `:reproduced?`, versioned
  content-addressed export bundles. Core `axiom.nomos`/`axiom.world`
  unchanged except an additive two-argument `world/replay` for snapshot
  restore; 0001 decision digests byte-identical on all four synthetic
  scenarios.
- `axiom.store` (SQLite adapter, the only namespace touching JDBC):
  open/close, WAL + busy timeout, schema v1 + forward-only v2 migration
  (stream/sequence covering index), transactional append with sequence
  assignment, UNIQUE enforcement on `event_id` and `dedup_key`, range
  reads, head/identity, snapshot store/load/take.
- `axiom.cli`: `replay --ledger PATH [--through SEQ]`,
  `replay --bundle PATH`, `export-bundle --ledger PATH --output PATH
  [--through SEQ]`; exits 0 valid / 4 invalid / 5 operational.

| Requirement | Implementation evidence |
| --- | --- |
| R1 (append-only event store, transactional sequence) | `store/append!` assigns the next sequence inside a single transaction (`test/axiom/store_test.clj`: `append-assigns-sequence`); `scripts/check` ledger gates append 4 synthetic events and replay them. |
| R2 (hash-chained, tamper-evident) | `ledger/chain-digest` / `verify-chain`; bit-flip and broken-link cases are operational failures (`ledger-test`: `chain-verification`). |
| R3 (dedup on event_id / dedup_key) | UNIQUE columns + deterministic pre-insert checks returning `:duplicate` with `:duplicate-event-id` / `:duplicate-dedup-key` reasons (`store-test`: `duplicate-append-rejected`; generated test redelivers a dedup key). |
| R4 (sequence-order replay, out-of-order observed times) | `ledger-world` reduces in `:seq` order (`ledger-test`: `out-of-order-observed-times`; generated test shuffles observed times). |
| R5 (decision reproduction) | `replay-report` recomputes each recorded scenario decision and reports `:reproduced?`; recorded decision IDs equal `nomos/evaluate` on the same inputs (`ledger-test`: `replay-report-reproduction`, `record-scenario-evaluates`); `scripts/check` asserts `:reproduced? true` on a seeded ledger. |
| R6 (read-only replay, 0/4/5 exits) | CLI opens ledgers without create/migrate; `scripts/check` asserts replay/export exits 0, missing file / bad `--through` / malformed bundle exit 4, tampered bundle digest exits 5. |
| R7 (forward-only migrations) | `schema_version` table; v1→v2 migration adds the covering index transactionally; payloads byte-identical across migration and replay-equivalent (`store-test`: `schema-migration-v1-to-v2`); newer-than-code schema is operational (`newer-schema-than-code-is-operational`). |
| R8 (rebuildable snapshots, replay equivalence) | `build-snapshot` / `verify-snapshot!` / `restore-world`; restore + suffix replay digest equals full replay digest (`ledger-test`: `snapshot-equivalence`; `store-test`: generated 14-envelope stream snapshots at a boundary); incompatible snapshots are ignored and rebuilt from events. |
| R9 (export bundles, content-addressed) | `export-bundle-data` / `read-bundle-data` with `:bundle/digest`; `scripts/check` replays a bundle and diffs decision IDs against the ledger replay (identical); tampered digest → operational (`ledger-test`: `bundle-round-trip`). |

Crash/adversarial (T7): an uncommitted writer insert rolled back on
close leaves no phantom event and the ledger keeps appending
(`store-test`: `interrupted-append-leaves-no-phantom`); malformed
envelopes are rejected before write and never admit actions
(`ledger-test`: `envelope-validation`, `record-event-shape`); stale
`prev_hash` appends are rejected (`stale-prev-hash-rejected`).

Exact commands run (from the repo root, `PATH` including Temurin 17.0.20):

- `git diff --check` — clean.
- `./scripts/clj -m axiom.test-runner` — 31 tests, 803 assertions, 0 failures, 0 errors.
- `./scripts/check` — all 0001 gates unchanged (evaluate allow 0 / missing 3 / stale 3 / failed 2; validate/status/next/explain exit 0) plus the ledger gates above.
- CLI probes: `replay --ledger` exit 0 with `:snapshot/used` and `:reproduced? true`; `replay --through 1` exit 0 with 2 decisions; `export-bundle` exit 0; `replay --bundle` exit 0 with decision IDs identical to the ledger replay; missing ledger / `--through 99` / `--through abc` / garbage bundle exit 4; tampered bundle digest exits 5.

## Limits and deferred work (unchanged from Accepted)

- Hash chaining is tamper-evident, not tamper-proof: an attacker who
  controls the whole database can rewrite the chain. The strengthened
  `verify-snapshot!` additionally checks the materialized world against
  its digest, so a tampered snapshot world with an intact digest is now
  rejected (found by the new tests during implementation).
- The local ledger is single-process; multi-writer coordination,
  replication and network protocols are out of scope.
- Inputs are unauthenticated; replay authorizes nothing.
- The design's remaining M2 items (local Git adapter, artifact digesting
  and retention, local diagnostic verification runner) stay deferred to
  `0003+`; `leases`/`action_outbox` belong to M4+.
- Test evidence is bounded (synthetic fixtures, generated sequences),
  not a formal proof or production trust attestation.
- Self-hosting is planned with independent evaluator/policy promotion
  gates; Axiom did not drive this implementation run.
