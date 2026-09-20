# 0002 — Durable ledger

Status: Accepted for implementation; verification pending.

Authority: the repository owner's 2026-09-20 instruction, "Start to implement
axiom", with the attached design plan, authorizes bounded slices. The owner's
2026-09-20 autopilot instruction authorizes continued bounded slices under the
spec-driven sequence (requirements → design → tasks → implementation →
verification), one focused branch/PR per slice. This records implementation
scope, not an independent security review or v1 acceptance. The larger design
remains proposed.

## Spec naming and scope decision (recorded 2026-09-20)

Spec `0001-offline-kernel` covers the bootstrap M0 work plus the M1 pure
kernel and its offline CLI. Spec `0002-durable-ledger` is the first M2 slice:
the durable event store, replayable snapshots, the `replay` CLI surface and
decision export bundles. It does NOT cover the whole proposed M2 milestone.
The design's other M2 items — the local Git adapter, artifact digesting and
retention, and the local diagnostic verification runner — are deferred to
later specs (`0003`, …). Likewise the design's `leases` and `action_outbox`
tables belong to action dispatch (M4+) and are explicitly out of this spec;
the ledger records decisions as events, it does not dispatch actions.

## Repository boundary (owner instruction, 2026-09-20)

The Axiom repository stays consumer-agnostic. Consumer-specific contracts,
policies, fixtures, scenarios and verification evidence belong in the consumer
repository (e.g. HomeKV), never here. All fixtures and examples in this spec
are synthetic and invented for tests.

## Requirements

- R1: A durable append-only event store persists validated events as EDN
  payloads in a single-process SQLite ledger. Each append assigns a
  monotonically increasing sequence number inside a transaction. The event
  envelope is: event ID, schema version, stream ID, sequence, deduplication
  key, producer, observed time, ingestion time, candidate ID, payload digest
  and payload. Events are hash-chained: each event records the canonical
  digest of its predecessor, and replay verifies the chain. The ledger is
  not called tamper-proof: an attacker who controls the whole database can
  rewrite the chain (per the design). The core reducer consumes events in
  ledger (sequence) order; external timestamps are data, never an assumed
  causal order.
- R2: Event identity and deduplication. Reusing an event ID is invalid (as in
  0001). A redelivered event with an already-recorded deduplication key is
  rejected as a duplicate append — deterministically, not silently
  deduplicated and not merged. Out-of-order observation arrival is handled
  deterministically: ingestion order assigns sequence; observed time does not
  reorder committed events. A crash during append must not create a committed
  event the writer believes failed: the append is one transaction, and a
  writer that did not see commit treats the append as unknown and re-reads
  the ledger rather than assuming success.
- R3: Schema versions and migration. The ledger records its schema version.
  Schema upgrades are numbered, transactional migrations; incompatible
  snapshots are discarded and rebuilt by replaying validated events, never
  silently trusted. Opening a ledger whose schema version is newer than the
  code supports is an operational failure, not a silent downgrade.
- R4: Rebuildable snapshots. A snapshot records derived world state with the
  covered sequence number, reducer version and a digest of the world plus the
  event-prefix head hash. Snapshots are a performance optimization only: a
  snapshot must equal a full replay of the events it covers (replay
  equivalence). A missing or incompatible snapshot falls back to replaying
  validated events from the ledger start.
- R5: Recorded-time replay. Time-dependent evaluations are recorded as
  explicit inputs (0001 semantics). Replaying a committed ledger prefix at
  the recorded evaluation time reproduces the historical world and decision
  byte-identically; evaluating the same prefix "now" may legitimately
  produce stale evidence. Identical inputs replay identically.
- R6: `replay` CLI. A read-only command reconstructs the world and the
  recorded decisions from a ledger (`--ledger PATH [--through SEQ]`) or from
  a decision export bundle (`--bundle PATH`). Output is EDN; exit 0 on a
  valid report, 4 on invalid input, 5 on operational failure. Readiness
  carries no gate semantics; automation must use `evaluate`.
- R7: Decision export bundles. A bundle is a versioned, content-addressed EDN
  document containing everything needed to reproduce a decision: bundle
  version, engine identity (Axiom version/commit, Clojure version, reducer
  version), ledger identity (schema version, head event hash, covered
  sequence range), the covered events, the scenario inputs with their
  explicit evaluation times, the decisions, and an optional snapshot
  reference. Reconstructing a decision from a bundle yields the same decision
  identity digest as the ledger replay it was exported from.
- R8: Core purity and adapter boundary. The evaluation core
  (`axiom.model`, `axiom.contract`, `axiom.world`, `axiom.prover`,
  `axiom.nomos`) stays pure and side-effect-free, with no production
  dependencies beyond Clojure. SQLite access lives in an explicit adapter
  namespace behind a pure-core port (append, range, snapshot store/load,
  replay source). Unknown or malformed required inputs cannot admit actions;
  malformed events fail validation and are never written.
- R9: Honest trust levels. Hash chaining detects some tampering but the
  local ledger is not tamper-proof. Locally recorded evidence is marked at
  its actual trust level; synthetic local results cannot masquerade as
  trusted remote evidence. As in 0001, producer names, approval references
  and scope observations in stored inputs are NOT authenticated, and an
  allow result is not authorization to execute or merge.

## Explicit boundaries

Single-process SQLite only; no multi-writer coordination, no replication, no
network protocol. No Git adapter (deferred to 0003+), no artifact retention
store beyond payload digests, no diagnostic verification runner, no worker
isolation, no action dispatch or enforcement (leases and the action outbox
belong to M4+), no merge capability, no GitHub observation. Crash behavior
testing covers the append protocol, not SQLite internals. Test evidence is
bounded observation, not formal proof. This slice does not change any 0001
decision bytes: the pure kernel and its exit contract are unchanged.
