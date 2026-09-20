# 0003 — Local observations (Git adapter, artifacts, diagnostic runner)

Status: Accepted for implementation; verification pending.

Authority: the repository owner's 2026-09-20 instruction, "Start to implement
axiom", with the attached design plan, authorizes bounded slices. The owner's
2026-09-20 autopilot instruction authorizes continued bounded slices under the
spec-driven sequence (requirements → design → tasks → implementation →
verification), one focused branch/PR per slice. This records implementation
scope, not an independent security review or v1 acceptance. The larger design
remains proposed.

## Spec naming and scope decision (recorded 2026-09-20)

Spec `0002-durable-ledger` covered the first M2 slice: the SQLite event store,
snapshots, replay and decision export bundles. This spec covers the rest of
the design's M2 item list: the local Git adapter (clean/dirty worktrees,
base/head/tree identities, full change enumeration), artifact digesting and
bounded retention metadata, and the local diagnostic verification runner
(with timeout/cancellation, its evidence marked at its actual trust level).
Leases and the action outbox remain M4+/M5 (action dispatch and supervised
execution); read-only GitHub observation is M3; merge and enforcement are M4.
This spec does NOT claim the M2 milestone gate; milestone acceptance belongs
to the design's M2 gate review.

## Repository boundary (owner instruction, 2026-09-20)

The Axiom repository stays consumer-agnostic. Consumer-specific contracts,
policies, fixtures, scenarios and verification evidence belong in the consumer
repository (e.g. HomeKV), never here. All fixtures and examples in this spec
are synthetic and invented for tests.

## Requirements

- R1: Read-only local Git observation. A pure `axiom.git` port and an
  explicit `axiom.adapters.git` adapter observe a local Git worktree and
  return validated observation maps, never mutating the repository. Each
  observation carries the design's Observation fields: producer identity
  (`:producer/id "axiom-local-git"` — an unauthenticated local producer),
  subject identity (repository path identity plus base/head/tree SHAs),
  value (clean/dirty state, branch/upstream identity where present, full
  change enumeration), provenance (command lines and exact `git` version
  run) and collection scope (the worktree root and the revisions compared).
  The observation includes base commit SHA, head commit SHA and tree SHA
  for the observed revision, and a complete change enumeration against
  base: added, modified, deleted and renamed paths with old/new names —
  enumeration that is incomplete is reported as incomplete, never as a
  complete diff.
- R2: Path safety. Renames, deletions, mode changes and Unicode paths are
  enumerated exactly. Symlinks and submodules are observed as typed entries
  with their targets, never followed: a symlink that escapes the worktree
  root (or whose target cannot be resolved deterministically) is reported
  as `:path/unsafe` and excluded from content digestion. Path traversal
  (`..` components escaping the worktree root in reported paths) is
  rejected, never normalized silently. These cases are handled or
  explicitly blocked; none of them may silently become trusted content
  identity.
- R3: Artifact digesting and bounded retention metadata. Files are digested
  as SHA-256 over exact bytes with media type, byte size and a location
  reference; the digest is content identity, not a trust statement
  (per the design: a checksum does not establish that a trustworthy test
  produced the artifact). The ledger gains an `artifacts` table recording
  content digests, media types, sizes, retention status and locations,
  matching the design's entity table. Retention is bounded and explicit:
  artifacts carry retention status (`:retained`, `:expired`,
  `:superseded`); retention bounds (maximum artifact payload bytes,
  maximum retained artifacts) are declared; exceeding a bound is an
  operational failure with an explicit reason, not a truncated success.
  Expired artifacts are marked expired, not silently dropped: their digest
  rows remain so past decisions stay reproducible from bundles.
- R4: Local diagnostic verification runner. A pure `axiom.runner` port and
  an explicit `axiom.adapters.runner` adapter execute configured
  verification commands and collect results. Commands are referenced by
  approved command ID with structured arguments — no shell interpolation;
  a recipe that intentionally uses a shell is an explicit, reviewed choice
  recorded in the run record. The runner enforces timeout, cancellation
  and output capture bounds; exceeding a bound (timeout, output cap)
  produces an `:incomplete` result with the bound named, never a truncated
  success. The collected result is a design-plan Evidence record: kind,
  producer (`:producer/id "axiom-local-runner"` — unauthenticated local),
  candidate identity it ran against, run identity, result, artifact digest
  of captured output, and applicability. Build scripts and tests are treated
  as arbitrary code per the design; this runner provides NO isolation
  beyond process timeout/cancellation/output caps — full worker isolation
  (CPU, memory, network) belongs to M5 and is explicitly not claimed.
- R5: Honest trust levels. Local Git observations and local runner results
  are marked at their actual trust level (`:trust/local-diagnostic`);
  they cannot masquerade as trusted remote CI. A synthetic local producer
  is test-only. An allow result is not authorization to execute or merge;
  observations authorize nothing.
- R6: Observations feed the ledger as events. Git observations and runner
  evidence records can be recorded through the 0002 append path as
  validated observation/evidence events (new event schema kind), so a
  decision replayed later shows the observations and rule results that
  produced it. Unknown or malformed observations are rejected by
  validation and cannot admit actions; a missing or incomplete
  observation makes the dependent obligation `:unknown`, never allowed.
- R7: CLI surface, read-only diagnostics. `axiom.cli observe-git
  --repo PATH [--base REV]` emits the EDN Git observation report;
  `axiom.cli digest --path FILE` emits the artifact digest record;
  `axiom.cli run --command ID [--args ...]` executes a configured
  diagnostic command and emits the Evidence record. `observe-git` and
  `digest` are read-only: they never create or migrate ledger files and
  never mutate the observed repository. `run` executes local commands and
  is honest about it: it mutates only the runner's configured working
  directory. Exit contract matches 0002: 0 on a valid report, 4 on
  invalid input (missing repo, unknown command ID, bad arguments), 5 on
  operational failure (git failure, timeout, retention bound exceeded,
  schema newer than supported).
- R8: Core purity and adapter boundary. The evaluation core
  (`axiom.model`, `axiom.contract`, `axiom.world`, `axiom.prover`,
  `axiom.nomos`, `axiom.ledger`) stays pure and side-effect-free, with no
  production dependencies beyond Clojure. Git access lives in
  `axiom.adapters.git` behind the pure `axiom.git` port; process
  execution lives in `axiom.adapters.runner` behind the pure
  `axiom.runner` port; artifact storage rows are owned by `axiom.store`
  (the only namespace touching SQLite). No other namespace shells out,
  spawns processes or touches the database file. Unknown or malformed
  required inputs cannot admit actions; malformed Git output that fails
  validation is an operational failure, never silently normalized.
- R9: Deterministic identities. Git identities are the repository's own
  object SHAs (base/head/tree); Axiom never invents a candidate identity.
  A dirty worktree has no stable tree identity for the worktree state
  itself — the observation reports `head` plus an explicit dirty file
  list, never a synthesized "dirty tree" SHA that could be confused with
  a commit.

## Explicit boundaries

No GitHub API or provider observation (M3). No execution of agents, no
action dispatch, no leases, no action outbox (M4+/M5). No merge capability
and no enforcement of any kind: this spec observes and digests only.
No worker isolation beyond timeout/cancellation/output caps. No
signatures or tamper-proofing: the local ledger remains tamper-evident,
not tamper-proof (0002 R9). No change to 0001/0002 decision bytes, exit
contracts or the 0002 schema-1/2 semantics beyond the additive `artifacts`
table. Test evidence is bounded observation, not formal proof. This spec
does not make local diagnostic evidence admissible as trusted remote CI.
