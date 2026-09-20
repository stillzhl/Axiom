# Verification record

State: spec authored and Accepted 2026-09-20; implementation in
progress — slice 1 (T1+T2, git observation port + adapter) landed
2026-09-20. This spec (0003) is NOT Verified, and no M2 milestone or
Axiom v1 acceptance is claimed from it. Verification of the
implementation will be recorded here when a future implementation
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
  kinds (T6), no artifact table (T3), no runner (T4). The spec as a
  whole is still NOT Verified.

## Limits and deferred work

- Implementation is in progress: `axiom.git` and
  `axiom.adapters.git` landed in slice 1 (2026-09-20); `axiom.runner`,
  `axiom.adapters.runner`, the `artifacts` table, and the
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
