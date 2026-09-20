# Tasks and acceptance gates

- [x] T1: Implement `axiom.store` (SQLite adapter): ledger open/close, WAL
  mode, schema creation (schema version 1), transactional append with
  sequence assignment, UNIQUE enforcement on `event_id` and `dedup_key`,
  event range reads, snapshot store/load. JDBC is the only new production
  dependency; it must be version- and SHA-256-pinned in
  `scripts/dependencies.lock` like the existing deps.
- [x] T2: Implement `axiom.ledger` (pure port): event validation via the
  0001 strict schemas, envelope construction with hash chaining, duplicate
  event-id and duplicate dedup-key rejection paths, replay-source reduction
  over stored envelopes using `axiom.world`, snapshot construction and
  verification (reducer version, world digest, head hash).
- [x] T3: Snapshot replay equivalence: `restore(snapshot at N) +
  replay(N+1..)` must equal full replay 0..end and the stored
  `world_digest`; incompatible snapshots (version or head-hash mismatch)
  are ignored and rebuilt from validated events. Cover with unit and
  generated tests (random event sequences with duplicate/out-of-order
  deliveries).
- [x] T4: Implement `axiom.cli replay` (`--ledger PATH [--through SEQ]`,
  `--bundle PATH`): thin adapter over the pure replay path; EDN report with
  ledger identity, world digest and recorded decisions; exit 0/4/5 contract
  per R6; broken hash chain and newer-than-supported schema are exit 5.
- [x] T5: Implement `axiom.cli export-bundle` (`--ledger PATH --output PATH
  [--through SEQ]`): versioned, content-addressed EDN bundle per the design;
  `replay --bundle` reproduces the same decision identity digests as the
  source ledger replay. Bundle digest is verified on read; a digest
  mismatch is an operational failure.
- [x] T6: Schema migration: `schema_version` table, numbered forward-only
  transactional migrations, newer-schema-than-code exits 5, migrations
  never rewrite stored event payloads. Test at least one migration path
  (create at v1, migrate, verify replay equivalence across the migration).
- [x] T7: Crash and adversarial tests: interrupted append leaves no phantom
  committed event (append transactionality); duplicate dedup-key appends are
  rejected deterministically; out-of-order observed times do not reorder
  reduction; unknown/malformed events are never written and cannot admit
  actions. Synthetic fixtures only.
- [x] T8: Verification and `scripts/check` gates: extend `scripts/check`
  with ledger gates (append a synthetic event stream, snapshot at a
  boundary, replay prefix and full ledger, export a bundle, replay the
  bundle, assert identical decision digests; assert the 0001 four-scenario
  exit contract is unchanged). Record exact commands, results and
  limitations in verification.md.

Acceptance: `./scripts/check` green (Temurin 17.0.20, Clojure 1.12.0);
`replay` on a committed ledger prefix reproduces the recorded decision
byte-identically (decision identity digests match the 0001 `evaluate`
results for the same inputs); snapshot restore + replay equals full replay;
bundle replay equals ledger replay; duplicate dedup-key appends rejected;
the 0001 exit contract (allow 0 / missing 3 / stale 3 / failed 2;
status/next/explain exit 0) is unchanged; CI green on a clean checkout.
A green test run supplies bounded test evidence, not a formal proof or
production trust attestation.
