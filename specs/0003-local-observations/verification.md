# Verification record

State: spec authored and Accepted 2026-09-20; implementation in
progress — slice 1 (T1+T2, git observation port + adapter) landed
2026-09-20, slice 2 (T3, artifact digesting + `artifacts` table, schema
v3) landed 2026-09-20. This spec (0003) is NOT Verified, and no M2
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

## Limits and deferred work

- Implementation is in progress: `axiom.git` and
  `axiom.adapters.git` landed in slice 1 (2026-09-20); artifact
  digesting and the `artifacts` table (schema v3) landed in slice 2
  (2026-09-20); `axiom.runner`, `axiom.adapters.runner`, and the
  `observe-git`/`digest`/`run` CLI commands do not exist yet.
- The local ledger remains single-process and tamper-evident, not
  tamper-proof (0002 R9, unchanged).
- Inputs are unauthenticated; local observations and runner results are
  marked `:trust/local-diagnostic` and authorize nothing.
- The runner will provide timeout/cancellation/output caps only; worker
  isolation (CPU, memory, network) belongs to M5.
- GitHub observation is M3; agent execution, action dispatch, leases,
  action outbox are M4/M5; merge and enforcement are not part of this
  spec or any accepted spec.
- Test evidence is bounded (synthetic fixtures), not a formal proof or
  production trust attestation.
- Self-hosting is planned with independent evaluator/policy promotion
  gates; Axiom did not drive this spec run.
- The local ledger remains single-process and tamper-evident, not
  tamper-proof (0002 R9, unchanged).
- Inputs are unauthenticated; local observations and runner results are
  marked `:trust/local-diagnostic` and authorize nothing.
- The runner will provide timeout/cancellation/output caps only; worker
  isolation (CPU, memory, network) belongs to M5.
- GitHub observation is M3; agent execution, action dispatch, leases,
  action outbox are M4/M5; merge and enforcement are not part of this
  spec or any accepted spec.
- Test evidence is bounded (synthetic fixtures), not a formal proof or
  production trust attestation.
- Self-hosting is planned with independent evaluator/policy promotion
  gates; Axiom did not drive this spec run.
