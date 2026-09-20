# Design

The durable ledger adds persistence under the existing pure kernel. All
reduction, evidence qualification and Nomos rules stay exactly as specified in
0001; the ledger changes how ordered events are stored and retrieved, not how
they are evaluated. The 0001 in-memory reduction is the reference
implementation that ledger replay must equal.

## Port and adapter

`axiom.ledger` (new, pure) defines the port as data contracts over plain
maps: `append` validates an event map and returns the envelope to store;
`replay-source` reduces a sequence of stored envelopes to a world using
`axiom.world`; snapshot maps are plain data. `axiom.store` (new, adapter)
owns every SQLite/JDBC call: opening/closing, transactions, schema creation
and migration, and the storage of envelopes and snapshots. No other namespace
touches the database file. The adapter is the only new production
dependency surface (a JDBC driver); the core keeps zero production
dependencies beyond Clojure.

## Event store

Tables (SQLite):

- `events`: `seq` INTEGER PRIMARY KEY (assigned in-transaction; the design's
  "transactionally assigned sequence numbers"), `event_id` TEXT UNIQUE,
  `schema_version` INTEGER, `stream_id` TEXT, `dedup_key` TEXT UNIQUE,
  `producer` TEXT, `observed_time` INTEGER, `ingested_time` INTEGER,
  `candidate_id` TEXT, `payload_digest` TEXT, `prev_hash` TEXT, `payload` TEXT
  (canonical EDN). `event_id` and `dedup_key` UNIQUE constraints make
  duplicate appends fail at the storage layer, deterministically.
- `schema_version`: single row, the ledger schema version, created with the
  ledger and advanced by numbered transactional migrations.
- `snapshots`: `seq` (covered sequence), `reducer_version` TEXT, `world_digest`
  TEXT, `head_hash` TEXT, `world` TEXT (canonical EDN of the reduced world).

WAL mode is enabled for durability. A single-process ledger is assumed; no
multi-writer locking protocol is specified here.

Event envelopes use the 0001 restricted EDN vocabulary and canonical
encoding v1, so `payload_digest` and the hash chain reuse
`axiom.model`'s SHA-256. `prev_hash` is the canonical digest of the
predecessor envelope (excluding its own `prev_hash` field, which is empty
for the genesis event). Replay verifies the chain prefix it consumes; a
broken chain is an operational failure, never a silent skip.

Ingestion: `append` validates the event against the strict 0001 schemas
before touching the database; invalid events are never written. Sequence is
assigned by the adapter inside the append transaction (max+1 under the
single-writer assumption). Ingestion time is recorded as an explicit input
supplied by the caller, consistent with 0001's "all time is an explicit
input" rule. Out-of-order arrival is therefore ordinary: the reducer sees
only sequence order.

Crash protocol: append is a single transaction. A caller that did not observe
commit must treat the append as unknown and re-read the ledger; the store
never reports success for an uncommitted write. On open, the store validates
the chain of the consumed prefix; `snapshots` with a mismatched
`reducer_version` or `head_hash` are ignored and the world is rebuilt from
validated events.

## Snapshots

A snapshot is taken at a sequence boundary by reducing events 0..N and
storing the world map with its digest and the head hash of event N. Replay
equivalence is a hard invariant: `restore(snapshot at N) + replay(N+1..)`
must produce a world whose canonical digest equals the digest of replaying
0..end from scratch, and equals `world_digest` in the snapshot row.
Snapshot policy (when to snapshot) is a caller concern: the store exposes
`take-snapshot`, the CLI policy (e.g. every K events) is specified in the
implementation tasks. Snapshots never replace events; events remain the
source of truth.

## Replay

Replay is a pure fold: `axiom.world` reduces the stored envelopes in
sequence order, applying the recorded evaluation times, reproducing the
0001 decision for each scenario recorded at each evaluation point. Replay at
the recorded time reproduces the historical decision; replay "now" applies
freshness rules against now and may legitimately defer. Replay does not
consult the network, the clock or the filesystem.

## `replay` CLI

`axiom.cli replay --ledger PATH [--through SEQ]` replays the committed
prefix (default: the full ledger) and emits EDN: ledger identity (schema
version, head hash, covered range), the world digest, and the recorded
decisions with their rule results. `axiom.cli replay --bundle PATH`
reconstructs from an export bundle instead of a live ledger. `replay` is
read-only: exit 0 on a valid report, 4 on invalid input (missing ledger,
bad arguments, unreadable bundle), 5 on operational failure (broken hash
chain, schema newer than supported, I/O error). The CLI stays a thin
adapter: argument parsing, file/ledger access, exit codes; all replay logic
is pure.

## Decision export bundles

`axiom.cli export-bundle --ledger PATH --output PATH [--through SEQ]`
writes a versioned EDN bundle:

```edn
{:bundle/version   1
 :engine           {:axiom/version "..." :axiom/commit "..."
                    :clojure/version "..." :reducer/version "..."}
 :ledger           {:schema/version 1 :head/hash "..."
                    :through/seq N :stream/id "..."}
 :scenario         {...}   ; 0001 scenario inputs, explicit times
 :events           [...]   ; stored envelopes, canonical
 :snapshot         {...}   ; optional snapshot reference used
 :decisions        [...]   ; recorded decisions with rule results
 :bundle/digest    "..."}  ; SHA-256 of the canonical encoding of the
                           ; bundle with :bundle/digest removed
```

Bundle reproduction is deterministic: `replay --bundle PATH` yields the
same decision identity digests as the ledger replay the bundle was exported
from. Bundles are self-contained and content-addressed; they carry no trust
beyond their contents (R9): a bundle from an unauthenticated ledger remains
unauthenticated evidence. The bundle format is versioned; a reader that does
not understand `:bundle/version` fails operationally rather than guessing.

## Migration

`schema_version` starts at 1. Migrations are numbered, forward-only,
transactional DDL+data scripts owned by `axiom.store`. Opening a ledger with
a newer schema version than the code supports exits 5 with a clear message.
Migration never rewrites history: events are immutable; a migration may add
tables/columns but not alter stored event payloads.

## Out of scope (explicit)

Multi-process/multi-writer ledgers; the local Git adapter; artifact
retention beyond digests; the diagnostic verification runner; leases and
the action outbox (M4+); execution, merge or enforcement of any kind;
tamper-proofing or signatures (beyond the honest hash chain); the design's
`--repo PATH` scenario form (still deferred to when consumer contracts
exist); any change to 0001 decision bytes or the 0001 exit contract.
