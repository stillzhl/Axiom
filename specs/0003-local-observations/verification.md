# Verification record

State: spec authored and Accepted 2026-09-20; implementation in
progress — slice 1 (T1+T2, git observation port + adapter) landed
2026-09-20, slice 2 (T3, artifact digesting + `artifacts` table, schema
v3) landed 2026-09-20, slice 3 (T4, diagnostic runner port + adapter)
landed 2026-09-20. This spec (0003) is NOT Verified, and no M2
milestone or Axiom v1 acceptance is claimed from it. Verification of
the implementation will be recorded here when a future implementation
slice lands.

## Spec acceptance evidence — 2026-09-20

Branch `docs/spec-0003-accepted`, docs-only change (no `src/`, `test/` or
`scripts/` edits). Inertness confirmation:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **31 tests, 803
  assertions, 0 failures, 0 errors** — unchanged from the 0002 baseline,
  confirming the docs-only change is inert. All 0001/0002 CLI gates
  (evaluate allow 0 / missing 3 / stale 3 / failed 2; status/next/explain
  exit 0; replay/export-bundle 0/4/5) pass.

The five spec files are written and internally consistent:
requirements.md (R1–R9, each testable), design.md (port/adapter
boundaries, Git observation, path safety, artifacts, runner, ledger
integration, CLI, migration), tasks.md (T1–T8 with acceptance notes),
this verification.md, and acceptance.md (spec vs implementation gates).

## Slice 1 evidence — T1+T2 git observation (port + adapter), 2026-09-20

Branch `feat/0003-git-observation`, one focused PR per the Axiom
autopilot (never pushed directly to `main`). No HomeKV-specific
content; all fixtures synthetic.

Delivered:

- `src/axiom/git.clj` — pure port: the observation schema (producer
  `{:producer/id "axiom-local-git" :producer/authenticated? false}`,
  trust `:trust/local-diagnostic`, subject repo path + `:git/base` /
  `:git/head` / `:git/tree` SHAs, value clean/dirty state +
  branch/upstream identity + full typed change enumeration,
  provenance command lines + exact `git --version`, collection scope
  worktree root + revisions compared), the change-set data model
  (`:added` / `:modified` / `:deleted` / `:renamed` / `:type-changed`
  with old/new paths and modes; incomplete enumeration is
  `:observation/incomplete` with the failing step named, never
  silently partial), and path-safety classification as data
  (symlinks typed with target and never followed; symlink targets
  escaping the worktree root, absolute, or unresolvable classified
  `:path/unsafe` with reason; submodules typed with commit, not
  recursed; any reported path with `..` escaping the root rejected
  operationally, never normalized). Validation via
  `axiom.contract/check-value!` plus exact-shape checks in the style
  of `axiom.ledger/validate-envelope!`. A dirty worktree reports the
  HEAD commit SHA plus the dirty file list; `:git/tree` is always the
  HEAD *commit's* tree — no synthesized "dirty tree" SHA is ever
  produced (R9). No process execution, no new production
  dependencies.
- `src/axiom/adapters/git.clj` — the only namespace invoking the
  `git` executable (R8). Read-only commands only: `git --version`,
  `git rev-parse` (toplevel/HEAD/branch/upstream/tree/base),
  `git merge-base`, `git status --porcelain=v1 -z`,
  `git diff --raw -z -M --no-abbrev <base> <rev>` (plus `git cat-file
  -p` to read symlink target bytes, never following), all via the
  `ProcessBuilder` pattern. Worktree paths are classified without
  following symlinks (`NOFOLLOW_LINKS`) and without recursing into
  submodules. Entry point `(observe! {:repo path :base rev})`
  returns a validated observation map; default base is the
  merge-base of HEAD and upstream when an upstream exists,
  otherwise the caller must pass `:base` (`:invalid` when missing).
  `axiom.cli`'s `git-commit` helper moved here as
  `current-commit-sha` (behavior byte-identical); `axiom.cli` rewired
  to call it — a repo-wide grep confirms no other namespace spawns
  processes or invokes git.
- `test/axiom/git_test.clj` — 14 tests / 95 assertions over
  synthetic temp-dir repos: clean-repo identities equal `git
  rev-parse` output; default base from upstream merge-base; dirty
  worktree (head + dirty file list, no dirty-tree SHA); change
  enumeration (added/modified/deleted/renamed with old→new
  names/type-change file→symlink); symlink escaping the worktree →
  `:path/unsafe` (committed and dirty cases); submodule → typed with
  commit, not recursed; `..`-escaping reported path → `:operational`;
  unresolvable base and non-repo dir → `:observation/incomplete`
  with the failing step named; repeat runs byte-identical;
  missing repo / missing base → `:invalid`. Registered in
  `test/axiom/test_runner.clj`.

Verification run (Temurin 17.0.20, Clojure 1.12.0): `./scripts/check`
green — **45 tests, 898 assertions, 0 failures, 0 errors** (baseline
was 31/803; +14 tests / +95 assertions from this slice). All
0001/0002 CLI exit gates unchanged (evaluate allow 0 / missing 3 /
stale 3 / failed 2; status/next/explain 0; replay/export-bundle
0/4/5; ledger tamper gate still exits 5).

Recorded deviations and limits:

- The task brief's literal `git diff --name-status -z -M
  --no-renames` was not used: `--no-renames` disables the `-M`
  rename detection the spec's R1 requires (renamed old→new), and
  `--name-status` drops the modes R2 needs for typed
  symlink/submodule entries. The implementation uses
  `git diff --raw -z -M --no-abbrev`, a strict superset carrying the
  same status letters plus modes and full-length SHAs. Rename
  ordering assumptions (`--raw -z`: old-then-new;
  `--porcelain -z`: new-then-old) were verified empirically against
  the installed git before the parser was written.
- `git diff --raw` abbreviates SHAs by default
  (`core.abbrev`); `--no-abbrev` was added after the first test run
  showed unparseable headers — the parser now requires full
  40-hex SHAs and reports anything else as
  `:observation/incomplete`.
- T1/T2 are complete; T3–T8 remain open. This slice adds no CLI
  commands (`observe-git`/`digest`/`run` are T5), no ledger event
  kinds (T6), no runner (T4). The spec as a whole is still NOT Verified.

## Slice 2 evidence — T3 artifact digesting and retention (schema v3), 2026-09-20

Branch `feat/0003-artifacts`, one focused PR per the Axiom autopilot
(never pushed directly to `main`). No HomeKV-specific content; all
fixtures synthetic.

Delivered:

- `src/axiom/model.clj` — `sha256-bytes`: SHA-256 over exact raw
  bytes, returning `"sha256:<64hex>"`, pure with no I/O (the caller
  supplies the byte array). Distinct from `digest`, which hashes the
  0001 canonical EDN encoding: `(sha256-bytes (.getBytes "abc"))` is
  `sha256:ba7816bf…15ad` while `(digest "abc")` hashes the canonical
  form — asserted different in tests. Non-byte-array input is
  `:invalid`.
- `src/axiom/ledger.clj` — `supported-schema-version` bumped 2 → 3.
- `src/axiom/store.clj` — the only namespace touching SQLite (R8):
  forward-only transactional migration v3 creating the `artifacts`
  table per the design DDL (`digest TEXT PRIMARY KEY, media_type TEXT
  NOT NULL, size_bytes INTEGER NOT NULL, retention TEXT NOT NULL,
  location TEXT NOT NULL, recorded_seq INTEGER NOT NULL`), plus the
  artifact API:
  - `record-artifact!` validates digest format (`sha256:<64hex>`),
    media type (non-blank, restricted charset in the shape of the
    existing `id?` — `[A-Za-z0-9][A-Za-z0-9._/+.-]{0,127}`, with `+`
    for suffixes like `application/atom+xml`; default
    `"application/octet-stream"` when not supplied — a content label,
    never a trust statement), size (non-negative integer), retention
    enum (`:retained`/`:expired`/`:superseded`, keyword or string;
    default `:retained`), location (non-blank reference, not a
    promise of availability), and `recorded_seq` (nil or
    non-negative — nil is stored as the `-1` sentinel per the NOT
    NULL DDL and reads back as nil). An optional `:artifact/bytes`
    cross-checks digest and size against the exact supplied bytes
    (`:digest-mismatch` / `:size-mismatch` are `:invalid`).
  - Bounded retention: the caller declares
    `{:max/artifact-bytes N :max/retained-artifacts M}` (both
    required, non-negative). Exceeding either is an operational
    failure with the bound and the reason named —
    `:max-artifact-bytes-exceeded` (`{:bound N :actual size}`) or
    `:max-retained-artifacts-exceeded` (`{:bound M :actual count}`),
    never a truncated success. The count bound counts rows with
    retention `retained`; expired/superseded rows do not count.
    Insert and bound checks run in one transaction, so a bound
    failure leaves no row.
  - `mark-artifact!` sets `:retained`/`:expired`/`:superseded` —
    rows are marked, never deleted, so past decisions stay
    reproducible. Unknown (well-formed) digest is `:invalid`
    (`:unknown-artifact`).
  - `read-artifact` / `list-artifacts` (ordered by digest, optional
    `{:retention …}` filter) apply read-back validation to every
    row. Exactly what read-back validation checks: digest matches
    `sha256:[0-9a-f]{64}`; media_type is a non-blank
    restricted-charset label; size_bytes is a non-negative integer;
    retention is one of `retained|expired|superseded`; location is a
    non-blank string; recorded_seq is an integer ≥ -1 (-1 reads back
    as nil). Any violation is an operational failure
    (`:malformed-artifact-row` with the offending `:column` named) —
    "detected on read", never silently normalized.
  - Duplicate digest is a deterministic `:duplicate` rejection
    (`:duplicate-artifact-digest`), checked before the retention cap
    so it reports identically at any cap state. Artifact identity is
    the digest.
- `test/axiom/artifacts_test.clj` — 10 tests / 130 assertions,
  registered in `test/axiom/test_runner.clj`, all over synthetic
  temp-dir ledgers and byte arrays:
  - `sha256-bytes` equals `sha256sum` output on the same bytes
    (with/without trailing newline, multi-line); known vectors for
    empty and `"abc"`; trailing-newline sensitivity; raw-bytes vs
    canonical-EDN distinction.
  - Record/read round-trip: media type/size/location recorded
    exactly; defaults (media type, `:retained`, nil recorded-seq);
    string retention normalizes to keyword; unknown digest reads nil;
    list ordering.
  - Input validation matrix (each `:invalid`): malformed digests,
    blank/bad-charset media types, negative/fractional/nil sizes,
    non-enum retention, blank/nil locations, negative recorded-seq,
    unknown fields, bytes/digest and bytes/size mismatches (named
    reasons), malformed bounds maps.
  - Retention bounds: oversize → `:operational` with bound and
    actual named; exact-bound payload allowed; over-count →
    `:operational` with bound and actual named; expired/superseded
    rows excluded from the cap; failed records leave no row.
  - Mark lifecycle: expired/superseded rows remain and stay
    queryable (past decisions reproducible); marking back to
    retained; unknown digest `:invalid` with `:unknown-artifact`.
  - Duplicate digest → deterministic `:duplicate` /
    `:duplicate-artifact-digest`, even at cap; original row untouched.
  - Tampered rows detected on read: direct-SQL tampering of each
    column (digest, retention, size_bytes, media_type) →
    `:operational` `:malformed-artifact-row` with the column named,
    via `read-artifact` and `list-artifacts`. A malformed *lookup*
    digest is `:invalid` input; the malformed *stored* digest is
    surfaced by `list-artifacts`.
  - Migration: v1 → v3 (sequential 1→2→3, v2 index present) and
    v1 → v2 (replicated exactly: covering index + version bump) → v3,
    each with events appended beforehand: raw `payload` TEXT
    byte-identical before/after, payload digests identical,
    `verify-chain` valid, `ledger-world` digest identical, and
    `replay-report` decisions vector identical with every decision
    `:reproduced?` (replay equivalence; the report's own
    `:ledger :schema/version` legitimately moves 1→3). 0001/0002
    decision bytes untouched. Artifacts recordable after migration.
    Opening a ledger with a newer schema than supported is still an
    operational failure (existing test, schema 999).

Verification run (Temurin 17.0.20, Clojure 1.12.0): `./scripts/check`
green — **55 tests, 1028 assertions, 0 failures, 0 errors** (baseline
was 45/898; +10 tests / +130 assertions from this slice). All
0001/0002 CLI exit gates unchanged (evaluate allow 0 / missing 3 /
stale 3 / failed 2; status/next/explain 0; replay/export-bundle
0/4/5; ledger tamper gate still exits 5). `git diff --check` clean.

Recorded deviations and limits:

- Media-type charset is `[A-Za-z0-9][A-Za-z0-9._/+.-]{0,127}` — the
  existing `id?` shape plus `+` for structured-syntax suffixes
  (`application/atom+xml`). The spec says "restricted character set
  like the existing `id?` shape"; the `+` addition is documented in
  the `media-type?` docstring.
- `recorded_seq` nil is stored as the `-1` sentinel (the design DDL
  is NOT NULL) and reads back as nil, so the API round-trips.
- "Mismatched" in the task brief is enforced at the record
  boundary: when `:artifact/bytes` are supplied, digest and size must
  match them (`:invalid` otherwise). On read, validation checks
  stored column shapes only. Documented honest limit: the ledger
  stores digests, never payload bytes (per the design), so a
  tampered digest that remains well-formed `sha256:<64hex>` is
  indistinguishable from a legitimately recorded different artifact
  at the row level — content-level checking belongs to caller-held
  bytes, not the row. Pinned by a test.
- The retention-cap count is evaluated after insert inside the same
  transaction; a duplicate digest therefore reports `:duplicate`
  deterministically regardless of cap state.
- T4–T8 remain open: `axiom.runner`, `axiom.adapters.runner`, the
  `observe-git`/`digest`/`run` CLI commands (T5), `:event/observation`
  and `:event/evidence` ledger kinds (T6), and the 0003 check-script
  gates (T8) do not exist yet. The spec as a whole is still NOT
  Verified.

## Slice 3 evidence — T4 diagnostic runner (port + adapter), 2026-09-20

Branch `feat/0003-diagnostic-runner`, one focused PR per the Axiom
autopilot (never pushed directly to `main`). No HomeKV-specific
content; all fixtures synthetic.

Delivered:

- `src/axiom/runner.clj` — pure port: the command registry schema as
  data with strict 0001-style validation (exact shapes, restricted
  types): `{:registry/version 1 :commands {id -> entry}}`, each entry
  declaring `:command/id` (the registry key must equal the entry's
  ID), `:command/executable` (absolute path), `:command/args` (a
  vector of fixed string literals and structured typed slot maps —
  `:string` with an explicit max length, `:enum` with a distinct
  value list, `:integer` with min/max bounds), `:command/workdir`
  (absolute path), `:command/timeout-seconds` (1..3600),
  `:command/stdout-cap-bytes` / `:command/stderr-cap-bytes`
  (1..16777216), `:command/applicability`, and `:runner/shell`
  (boolean, declared explicitly — there is no default).
  Run-request validation: known command ID, args supplying exactly
  the declared slots, typed slot values, and no shell interpolation
  — when `:runner/shell` is false, a caller-supplied slot value
  containing shell metacharacters is rejected as `:invalid`; when
  true, the flag is an explicit reviewed choice recorded verbatim in
  the run record. Arguments render into an argv vector (executable
  first, literals verbatim, slots as their string form). The Evidence
  record schema: `:run/kind :diagnostic-run`, run identity, producer
  `{:producer/id "axiom-local-runner" :producer/authenticated?
  false}`, trust `:trust/local-diagnostic`, candidate identity
  (base/head/tree 40-hex SHAs or nil), the recorded argv, the
  registry's bounds verbatim, `:runner/shell` verbatim, validated
  request args, applicability, the exact isolation limitations text,
  `:run/outcome` (`:completed` / `:timed-out` / `:cancelled` /
  `:output-capped`), `:run/complete?`, `:run/result` (`:pass` /
  `:fail` / `:incomplete`), `:run/exit` (recorded exactly for
  `:completed`, nil otherwise), SHA-256 digests of the captured
  stdout/stderr bytes (via `axiom.model/sha256-bytes`, the artifact
  digest format, so a later slice can record the bytes as an
  artifact), captured byte counts, and `:run/bound-exceeded` naming
  the violated bound (`:timeout-seconds` / `:stdout-cap-bytes` /
  `:stderr-cap-bytes`) or nil. Consistency is enforced: every outcome
  except a clean `:completed` within bounds is `:run/complete? false`
  / `:run/result :incomplete`; a non-zero exit is still `:completed`
  with `:result :fail` (honest about the result). `build-run-record`
  is deterministic from validated inputs alone. No process execution,
  no I/O, no new production dependencies.
- `src/axiom/adapters/runner.clj` — the only namespace spawning
  processes (R8). Loads the checked-in registry
  (`resources/axiom/run-registry.edn`, strict EDN read plus
  `validate-registry!`); an explicit registry map may be passed
  instead — `:registry nil` selects the checked-in one, which is
  what tests use for overrides. `run!` runs one approved command
  synchronously and returns the validated Evidence record; `start!`
  returns `{:run/id :future :cancel! :plan}` and `cancel!` requests
  cancellation (true when the run was still active, false after it
  settled). Spawns via ProcessBuilder with the argv vector — never a
  shell string. The timeout kills the process on expiry; a 50 ms
  poll loop makes cancellation prompt; stdout/stderr are pumped in
  separate threads up to the declared caps with one-byte overflow
  detection. Spawn failures are `:operational`.
  `clojure.core/run!` is excluded via `(:refer-clojure :exclude
  [run!])` since the adapter defines its own `run!`.
- `resources/axiom/run-registry.edn` — the checked-in bounded
  registry: four generic synthetic probes only (`true-probe`,
  `false-probe`, `sleep-probe` with an integer `seconds` slot 0..30
  and a 5 s timeout, `echo-probe` with a string `message` slot up to
  256 chars). Nothing consumer-specific. All four resolve to real
  binaries (`/bin/true`, `/bin/false`, `/bin/sleep`, `/bin/echo`),
  verified present and executable on this Linux box.
- `test/axiom/runner_test.clj` (registered in `test_runner.clj`) —
  10 new tests: registry validation (unknown command ID, key/ID
  mismatch, bad timeout/cap/executable/workdir, implicit
  `:runner/shell` rejected, per-type slot shape strictness);
  run-request validation (exact slot coverage, mistyped /
  out-of-range / off-enum values, shell metacharacter rejection
  across `$(...)`, `;`, `|`, backticks, `&`, `${...}`, argv rendering
  order, applicability default vs override); `:runner/shell true`
  recorded verbatim (and false too); Evidence construction
  (trust/producer marks, exact limitations text,
  `:pass`/`:fail`/`:incomplete` derivation, inconsistency
  rejections, determinism of the pure paths); adapter runs
  (`true-probe` → `:completed`/`:pass`, digests of empty output;
  `echo-probe` stdout digest equals `sha256-bytes` of the exact
  emitted bytes; `false-probe` → `:completed`/`:fail`;
  `sleep-probe` 30 s against the 5 s registry timeout →
  `:timed-out` + `:incomplete` with `:bound-exceeded
  :timeout-seconds`, run settled well under 30 s; 100-char message
  against a 16-byte cap → `:output-capped` + `:incomplete`, exactly
  16 bytes captured, digest of those bytes; cancellation →
  `:cancelled` + `:incomplete`, `cancel!` true then false after
  settle; explicit registry override honored and the checked-in
  registry not consulted; unknown command / bad args / shell attempt
  via the adapter → `:invalid` without spawning).

Test results:

- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **65 tests,
  1169 assertions, 0 failures, 0 errors** — up from the 55/1028
  baseline (+10 tests, +141 assertions). All 0001/0002 CLI gates
  (evaluate allow 0 / missing 3 / stale 3 / failed 2;
  status/next/explain exit 0; replay/export-bundle 0/4/5) pass;
  `git diff --check` clean.

Recorded deviations and limits:

- `:runner/shell` must be declared explicitly in every registry
  entry (true or false); absence is `:invalid`. The brief's "never a
  default" is enforced structurally: there is no default value, only
  an explicit reviewed choice.
- When `:runner/shell` is true the adapter still spawns the argv
  vector via ProcessBuilder — Axiom itself never interpolates
  through a shell; the flag documents that the recipe is
  shell-aware and is recorded verbatim in the run record. No
  shell-using recipe is checked in.
- `:run/complete?` is exactly `(= :completed outcome)`; it is a
  first-class validated field, not derived at read time, so an
  incomplete run can never satisfy an evidence obligation.
- The exit code is recorded only for `:completed` outcomes (nil
  otherwise), even when a capped, cancelled or timed-out process
  happened to exit on its own — the violated bound or the
  cancellation is the story, not an exit status.
- Timestamps are deliberately absent from the run record: the record
  is deterministic from validated inputs alone (pure); ledger events
  carry observed/ingested times at T6.
- T5–T8 remain open: the `observe-git`/`digest`/`run` CLI commands
  (T5), `:event/observation` and `:event/evidence` ledger kinds (T6),
  the git-side adversarial tests and the 0003 check-script gates
  (T8) do not exist yet. The spec as a whole is still NOT Verified.

## Limits and deferred work

- Implementation is in progress: `axiom.git` and
  `axiom.adapters.git` landed in slice 1 (2026-09-20); artifact
  digesting and the `artifacts` table (schema v3) landed in slice 2
  (2026-09-20); `axiom.runner` and `axiom.adapters.runner` landed in
  slice 3 (2026-09-20); the `observe-git`/`digest`/`run` CLI commands
  do not exist yet.
- The local ledger remains single-process and tamper-evident, not
  tamper-proof (0002 R9, unchanged).
- Inputs are unauthenticated; local observations and runner results are
  marked `:trust/local-diagnostic` and authorize nothing.
- The runner provides timeout/cancellation/output caps only; worker
  isolation (CPU, memory, network) belongs to M5 and is not claimed —
  the run record's limitations field says exactly this.
- GitHub observation is M3; agent execution, action dispatch, leases,
  action outbox are M4/M5; merge and enforcement are not part of this
  spec or any accepted spec.
- Test evidence is bounded (synthetic fixtures), not a formal proof or
  production trust attestation.
- Self-hosting is planned with independent evaluator/policy promotion
  gates; Axiom did not drive this spec run.
