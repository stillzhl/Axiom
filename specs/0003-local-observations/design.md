# Design

Spec 0003 completes the design's M2 item list under the existing pure
kernel: Git observations, artifact digesting with bounded retention
metadata, and a local diagnostic verification runner whose evidence is
honestly marked. The 0001 evaluation semantics and the 0002 ledger
semantics are unchanged; this spec adds observation sources that feed
them.

## Ports and adapters

```
axiom.git            (pure port: observation validation, change-set data)
axiom.adapters.git   (adapter: shells the `git` executable, parses output)
axiom.runner         (pure port: run-request validation, evidence records)
axiom.adapters.runner(adapter: spawns processes, enforces timeout/cancel)
axiom.store          (adapter: extended with the `artifacts` table)
axiom.cli            (thin: observe-git / digest / run, exit codes)
```

`axiom.git` defines the observation schema, the change-set data model and
the path-safety rules as data. It validates adapter-produced maps with
the strict 0001 EDN schemas; invalid observations are rejected before
they can enter the ledger or the reducer. `axiom.adapters.git` is the
only namespace that invokes the `git` executable; it treats all Git
output as untrusted text until validated. No JGit or other Git library
dependency is introduced — the adapter boundary is the installed `git`
CLI, and its version is recorded in every observation's provenance.

`axiom.runner` defines the command registry schema (command ID,
executable, allowed argument shapes, working directory, timeout,
output caps) and the Evidence record schema. `axiom.adapters.runner`
spawns the process, enforces the bounds and builds the record. The CLI
stays thin: argument parsing, adapter calls, EDN output, exit codes.

## Git observation

`observe-git` collects, in order:

1. Repository identity: worktree root, `git --version`, `git rev-parse`
   for `HEAD`, the current branch and upstream (if any).
2. Status: `git status --porcelain=v1 -z` (NUL-delimited, no locale
   dependence) to determine clean vs dirty and enumerate worktree
   changes.
3. Change enumeration against base: `git diff --name-status -z -M
   --no-renames`... (exact flags fixed in implementation) between the
   base revision and the observed revision, producing the typed
   change list: added, modified, deleted, renamed (old→new), type-changed.
   If any porcelain command exits nonzero or emits unparseable output,
   the observation is `:observation/incomplete` with the failing step
   named — never a silently partial diff.

Identities reported: `:git/base` (base commit SHA), `:git/head`
(head commit SHA), `:git/tree` (head tree SHA). The default base is the
merge-base of head and upstream when an upstream exists; otherwise the
caller must pass `--base`. A dirty worktree is reported as `:git/dirty`
with the porcelain file list; no synthetic dirty-tree SHA is produced
(R9). This keeps local observations reproducible: the same
base/head/worktree inputs produce the same observation, and identical
inputs replay identically.

## Path safety

`axiom.git` classifies every reported path before content digestion:

- Symlinks are reported as `{:path/kind :symlink :path/target "..."}`
  and are never followed for content. A symlink whose target escapes
  the worktree root, is absolute, or cannot be resolved
  deterministically is classified `:path/unsafe` and excluded from
  digestion, with the reason recorded.
- Submodules are reported as `{:path/kind :submodule
  :path/commit "..."}` and their contents are not enumerated (no
  recursion into foreign repositories).
- Any reported path containing `..` components that would escape the
  worktree root is rejected: the observation fails validation and the
  adapter reports an operational failure (R7 exit 5) rather than
  normalizing.
- Mode changes and Unicode paths are enumerated exactly as Git reports
  them (NUL-delimited output, no locale mangling).

This satisfies the M2 gate: renames, deletions, symlinks and submodules
are handled (typed enumeration) or explicitly blocked (`:path/unsafe`
exclusion).

## Artifacts

The `artifacts` table (new, schema version 3, forward-only migration
from v2 — 0002's migration rules apply):

```sql
artifacts(
  digest         TEXT PRIMARY KEY,   -- sha256 of exact bytes
  media_type     TEXT NOT NULL,
  size_bytes     INTEGER NOT NULL,
  retention      TEXT NOT NULL,      -- retained | expired | superseded
  location       TEXT NOT NULL,      -- reference, not a promise of availability
  recorded_seq   INTEGER NOT NULL    -- ledger seq of the recording event
)
```

Digests use `axiom.model`'s SHA-256 over exact file bytes (the 0001
canonical encoding is for EDN maps; artifact bytes are hashed raw).
The digest is content identity only: per the design, it does not
establish that a trustworthy producer created the bytes. Payloads
larger than the configured maximum, or a count beyond the retention
cap, are rejected with an explicit operational failure — retention
bounds are declared in the run record, and exceeding them never yields
a truncated success. Expiry/supersede marks rows; rows are never
deleted, so past bundles and decisions stay reproducible.

Observation and evidence events reference artifacts by digest only; the
ledger never embeds arbitrary binary payloads.

## Diagnostic runner

Command registry: a bounded EDN document (checked in, not caller-supplied
at runtime) mapping command IDs to executable path, fixed argument
templates with structured (typed) argument slots, working directory,
timeout seconds and output byte caps. No shell interpolation: arguments
are passed as an argv vector. A recipe that intentionally needs a shell
declares `:runner/shell true`, which is recorded verbatim in the run
record — an explicit reviewed choice, not a default.

Run protocol: the adapter spawns the process, captures stdout/stderr up
to the caps, enforces the timeout (kill on expiry) and supports
cancellation. Outcomes: `:completed` (with exit code), `:timed-out`,
`:cancelled`, `:output-capped` — all marked `:incomplete` except a
clean `:completed` within bounds. An incomplete run can never satisfy
an evidence obligation; the dependent obligation becomes `:unknown`.
The run record becomes a design-plan Evidence entity: kind, producer
`axiom-local-runner`, candidate identity (the base/head/tree the command
ran against), run identity, result, digest of captured output stored as
an artifact, applicability notes. Trust is marked `:trust/local-diagnostic`
(R5): the record cannot be promoted to trusted-remote-CI evidence.

Isolation honesty: the runner is a local diagnostic. It provides process
timeout, cancellation and output caps only. CPU/memory/network sandboxing
is M5 work and is not claimed here; the run record's limitations field
says exactly this.

## Ledger integration

New event schema kind `:event/observation` and `:event/evidence`
(validated by `axiom.ledger`'s strict schemas) carry the Git
observation maps and runner Evidence records, referencing artifact
digests. They flow through the 0002 append path: hash-chained,
deduplicated by `event_id`/`dedup_key`, replay-ordered by sequence,
covered by snapshots and included in export bundles. `replay` shows the
observations and evidence that produced each decision, satisfying the
design's maintainer journey ("replays a past decision and sees the
policy, observations, and rule results that produced it").

## CLI

- `observe-git --repo PATH [--base REV]`: read-only Git observation
  report (EDN), exit 0 valid / 4 invalid input / 5 git or validation
  failure. Never creates ledgers, never mutates the repo.
- `digest --path FILE`: artifact digest record (EDN) with
  SHA-256/media-type/size; exit 0/4/5.
- `run --command ID [--args k=v ...]`: executes a registry command
  against the configured working directory, emits the Evidence record
  (EDN), exit 0/4/5; timeout/cancellation/retention-bound failures are
  exit 5 with the reason named.

## Migration

Schema version 3 adds the `artifacts` table, forward-only and
transactional, following the 0002 migration rules: never rewrites
stored event payloads; replay equivalence holds across the migration;
opening a ledger with a newer schema than the code supports exits 5.

## Out of scope (explicit)

GitHub provider observation (M3); agent execution, action dispatch,
leases, action outbox (M4/M5); merge or any enforcement; worker
isolation beyond timeout/cancellation/output caps; signatures and
tamper-proofing; recursion into submodules; following symlinks;
synthesizing dirty-tree identities; any change to 0001/0002 decision
bytes or exit contracts.
