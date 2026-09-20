# Verification record

State: spec authored and Accepted 2026-09-20; implementation landed in
four slices and **Verified** 2026-09-20. No M3 milestone or Axiom v1
acceptance is claimed from this spec alone; milestone gates belong to
the design's M3 gate review.

## Spec acceptance evidence — 2026-09-20

Branch `docs/spec-0004-accepted`, docs-only change (no `src/`, `test/` or
`scripts/` edits). Inertness confirmation:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **74 tests,
  1280 assertions, 0 failures, 0 errors** — unchanged from the 0003
  baseline, confirming the docs-only change is inert. All 0001/0002/0003
  CLI gates pass (evaluate allow 0 / missing 3 / stale 3 / failed 2;
  status/next/explain exit 0; replay/export-bundle 0/4/5; ledger tamper
  gate exit 5; observe-git/digest/run 0/4/5).

The five spec files are written and internally consistent:
requirements.md (R1–R9, each testable), design.md (port/adapter
boundaries, observation model, pagination/completeness, retries and
error handling, identity and trust, matrix/attempts/approvals,
check-pr and advisory can-merge, ledger integration, fixtures and
offline reproduction, CLI), tasks.md (T1–T8 with acceptance notes),
this verification.md, and acceptance.md (spec vs implementation gates).

## Slice 1 implementation evidence — 2026-09-20

Branch `feat/0004-github-port`: pure `axiom.github` port plus pure
`check-pr` (T1; the pure behavior described in T4), synthetic tests
only. No network, no I/O, no new production dependencies; nothing
HomeKV-specific; no real repositories, credentials, or live-network
tests.

New files:

- `src/axiom/github.clj` — pure GitHub observation schema
  (`:observation/kind :github-observation`), pagination data model
  (every collection carries `:pagination/complete` and
  `:pagination/pages`; incomplete observations are
  `:observation/incomplete` and name the failing collection/page),
  identity rules (repo, PR, base/head SHAs, fork head-repo owner/name,
  run/job/attempt/artifact identity), trust marks
  (`:trust/provider-observed` / `:trust/provider-authenticated`;
  `:trust/remote-ci` rejected), strict 0001-style EDN validation
  (`build-observation` rejects unknown input fields; malformed/unknown
  observations are `:invalid` and cannot admit actions), check
  conclusion vocabulary (unknown maps to `:unknown`, never success),
  and pure `check-pr` (per-gate `:pass`/`:fail`/`:unknown` bound to
  exact base/head SHAs with exact evidence links, advisory-only
  `can-merge`, `:stale` via `stale?`/`report-status` when SHAs move,
  explicit matrix/attempt selection rules, skipped/failed/unknown
  conclusions and dismissed/stale-head approvals make obligations
  `:unknown`).
- `test/axiom/github_test.clj` — 18 synthetic-only tests (invented
  org/repo, SHAs, actors, and `api.github.com` evidence URLs).

Edited:

- `test/axiom/test_runner.clj` — runs `axiom.github-test`.

Evidence:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **92 tests,
  1427 assertions, 0 failures, 0 errors** — up from the 0003 baseline
  (74 tests, 1280 assertions). All 0001/0002/0003 CLI gates unchanged
  and passing (evaluate allow 0 / missing 3 / stale 3 / failed 2;
  status/next/explain exit 0; replay/export-bundle 0/4/5; ledger tamper
  gate exit 5; observe-git/digest/run 0/4/5).

Tasks: T1 is complete. T4 is intentionally left unchecked: every pure
behavior its text describes is implemented and tested above, but its
consumers (CLI in T5, ledger wiring in T6) do not exist yet, so no
broader completion is claimed from this slice.

## Slice 2 implementation evidence — 2026-09-20

Branch `feat/0004-github-adapter`: T2 network adapter plus T3
identity plumbing. Synthetic fixtures only; no network, no live
credentials, no real repository identities (invented
`synth-org/synth-repo`, synthetic 40-hex SHAs, `synth-*` logins,
`synth-token-*` credentials); nothing HomeKV-specific.

New files:

- `src/axiom/adapters/github.clj` — the ONLY namespace touching the
  network. JDK `java.net.http.HttpClient`; no new production
  dependencies. Exactly one request constructor (`build-get-request`,
  a single `(.GET)` call site — grep-verified, no `.POST`/`.PUT`/
  `.PATCH`/`.DELETE` construction anywhere). Declared client config
  (API base/version, user agent, connect/request timeouts, bounded
  retries and backoff, bounded ETag cache) recorded in observation
  provenance. Minimal internal JSON reader (used only by the real
  network fetch). Link `rel="next"` pagination to the end of every
  collection (changes, runs, reviews, artifacts, plus nested run/job
  attempts); a page failing after bounded retries yields
  `:observation/incomplete` naming the collection, page and retry
  bound — incomplete collections never carry partial items, and a
  nested jobs failure keeps the failing run with its job list marked
  incomplete. 429 and transient 5xx retried with backoff honoring
  `Retry-After` and `X-RateLimit-Reset`; 401/403/404 (and any other
  unexpected status) are terminal operational failures naming status
  and resource. Strict per-field provider payload validation —
  violations are operational failures naming the offending field;
  unknown check conclusions map to `:unknown`, never success. Bounded
  ETag cache keyed by [owner repo resource url] storing ETag, body
  and the base/head SHAs recorded under; stale entries are
  invalidated and a 304 for a stale entry is an operational failure.
  Identity: credential resolved out-of-band only (env map defaulting
  to the real environment, or a readable token file — a raw `:token`
  argument is `:invalid`); token identity resolved once per
  observation via the provider `/user` endpoint; authenticated
  observations marked `:trust/provider-authenticated`, anonymous ones
  `:trust/provider-observed`; every produced observation is validated
  with `axiom.github/validate-observation!` before return. Fetch is
  injectable (`:fetch-fn` / `fixture-fetch`).
- `test/axiom/adapters_github_test.clj` — 30 tests / 100 assertions:
  multi-page pagination to completion; mid-list page failure naming
  collection/page/bound (`:observation/incomplete`, no partial items);
  nested jobs page failure naming the run; 429 honoring `Retry-After`
  then succeeding; 429 honoring `X-RateLimit-Reset`; retry exhaustion
  naming the bound; 401/403/404 terminal without retries; malformed
  payloads (unknown file status, missing PR title, malformed run SHA,
  PR number mismatch) naming the field; stale ETag cache hit
  operational after base movement; 304 reuse under unchanged SHAs;
  anonymous vs token-file/env authenticated trust; token identity
  resolved exactly once per observation; 401 on the identity endpoint
  operational without leaking the token; forged-producer tampering
  (`:invalid`); duplicate run IDs, run/job conclusion mismatches,
  job/run association mismatch as named operational failures; unknown
  conclusions mapping to `:unknown`; passing job inside a failing run
  accepted; fixture fetch with no fixture operational; GET-only
  grep assertion; JSON reader cases.

Edited:

- `test/axiom/test_runner.clj` — runs `axiom.adapters-github-test`.

Evidence:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **122 tests,
  1527 assertions, 0 failures, 0 errors** — up from the Slice 1
  baseline (92 tests, 1427 assertions). All 0001/0002/0003 CLI gates
  unchanged and passing (evaluate allow 0 / missing 3 / stale 3 /
  failed 2; status/next/explain exit 0; replay/export-bundle 0/4/5;
  ledger tamper gate exit 5; observe-git/digest/run 0/4/5).
- Adapter source grep: `\(.GET\)` occurs exactly once (the request
  constructor); no `.POST`/`.PUT`/`.PATCH`/`.DELETE` occurrences —
  GET-only by construction.

Tasks: T2 and T3 are complete. T4's checkbox stays as decided in
Slice 1 (pure behavior implemented in the port, consumers pending in
T5/T6). The `--token-file` CLI flag surface belongs to T5; the
adapter-level credential contract (env/token-file options, raw token
rejection) is done here. No milestone Verified claim is made from
this slice.

## Slice 3 implementation evidence — 2026-09-20

Branch `feat/0004-github-cli-ledger`: T5 CLI plus T6 ledger
integration. Synthetic fixtures only; no network, no live
credentials, no real repository identities (invented
`synth-org/synth-repo`, synthetic 40-hex SHAs, `synth-*` logins,
`synth-token-*` credentials); nothing HomeKV-specific.

New files:

- `test/axiom/github_cli_ledger_test.clj` — 11 synthetic-only tests
  (T5 CLI argument parsing and exit codes, fixture-mode observations
  anonymous and authenticated, `--sha` head pinning, operational
  failure mapping, CLI read-only guarantee, T6 ledger append/replay/
  trust validation/dedup, CLI replay of provider observations,
  snapshot and export-bundle inclusion, deterministic fixture
  replay).

Edited:

- `src/axiom/cli.clj` — `observe-github --repo OWNER/NAME --pr N
  [--sha SHA] [--token-file PATH]` and `check-pr --repo OWNER/NAME
  --pr N [--token-file PATH]`: thin adapters over the pure
  `axiom.adapters.github/observe!` and `axiom.github/check-pr` paths.
  EDN reports on stdout. Exit contract per R7: 0 on a complete valid
  report, 4 on invalid input (malformed repo slug, bad PR number,
  unknown/bogus flags, duplicate flags, bad SHA shape, unreadable
  token file, `--sha` mismatch against the observed head), 5 on
  operational failure (terminal API error, incomplete observation,
  stale cache). `--token-file` reads the token from a file;
  otherwise the adapter resolves `AXIOM_GITHUB_TOKEN`. Raw tokens are
  never accepted as arguments. An `AXIOM_GITHUB_FIXTURES` hook
  injects the port's fixture fetch for offline runs (used by the
  test suite; no network). `check-pr` uses the default generic gates
  `:pr-identity`, one required approval, `:merge-state`; the
  advisory-only `can-merge` summary is emitted, never enforcement.
  Neither command opens, migrates or writes ledger files
  (test-redefined store writes throw; both commands still exit 0).
- `src/axiom/ledger.clj` — `record-observation` now dispatches on
  `:observation/kind`: `:git-observation` keeps requiring
  `:trust/local-diagnostic`; `:github-observation` accepts only
  `:trust/provider-observed` / `:trust/provider-authenticated`.
  `:trust/remote-ci`, unknown kinds, malformed observations and
  trust-kind mismatches are `:invalid` and can never be written.
  The 0002 append path (transactional sequence, hash chain,
  event-id/dedup-key dedup, snapshots, export bundles) is unchanged.
- `test/axiom/test_runner.clj` — registers the new test namespace.

Evidence:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **133 tests,
  1627 assertions, 0 failures, 0 errors** — up from the Slice 2
  baseline (122 tests, 1527 assertions). All 0001/0002/0003 CLI gates
  unchanged and passing (evaluate allow 0 / missing 3 / stale 3 /
  failed 2; status/next/explain exit 0; replay/export-bundle 0/4/5;
  ledger tamper gate exit 5; observe-git/digest/run 0/4/5).

Honest limits:

- `observe-github` requires `--pr` in this slice. R7 writes the flag
  as `[--pr N]` (optional), but the 0004 observation schema and
  adapter (T1–T4) are PR-centric — a repo-at-SHA observation without
  a PR has no defined observation shape — so the CLI rejects a
  missing `--pr` as invalid input (exit 4). Repo-level observation
  is deferred, not silently supported.
- `check-pr`'s default gates are generic (`:pr-identity`, one
  required approval, `:merge-state`); `:required-checks` needs
  repository-specific job names the CLI has no input for. Pure
  consumers call `axiom.github/check-pr` with explicit gates.
- No `check-pr` report is persisted as a ledger decision record:
  the advisory report is recomputed byte-identically from the
  replayed observation (tested), which is the honest form of
  "fixture-recorded decisions replay offline" for an advisory-only
  command.

Tasks: T5 and T6 are complete. No milestone Verified claim is made
from this slice.

## Slice 4 verification evidence — 2026-09-20

Branch `feat/0004-github-verification`: T7 adversarial/boundary tests
plus T8 `scripts/check` gates. Synthetic fixtures only; no network, no
live credentials, no real repository identities (invented
`synth-org/synth-repo`, synthetic 40-hex SHAs, `synth-*` logins,
`synth-token-*` credentials); nothing HomeKV-specific.

New files:

- `test/axiom/github_adversarial_test.clj` — 4 tests covering the only
  genuine gaps the T7 audit found (most T7 cases were already covered
  by slices 1–3; see tasks.md): 429 rate-limit exhaustion on a
  collection yields `:observation/incomplete` naming the bound and the
  429 status (never a partial list); a fork PR observed end to end
  carries the forker's owner/name as `:github/head-repo`, and
  `check-pr` keeps fork and upstream refs distinct; expired artifacts
  are recorded with their expiry intact (epoch seconds), and malformed
  `expires_at`/`digest` fields are operational failures naming the
  field; a static test scans all `test/` sources for socket/network
  API references (`java.net.http`, `HttpClient`, socket classes) and
  fails if any test could open a socket — every test runs the full
  adapter logic against injected fixtures.
- `examples/synthetic-github/fixtures.edn` — the recorded synthetic
  provider fixture set driving the check gates (PR, 2 changed-file
  pages, 1 run with 1 job, 1 approval on the head SHA, 1 artifact;
  anonymous observation).
- `examples/synthetic-github/fixtures-pagination-failure.edn` — the
  changed-files page 2 fails with 503 on every attempt, exhausting the
  bounded retries.

Edited:

- `test/axiom/test_runner.clj` — registers the new test namespace.
- `scripts/check` — a 0004 gates section driven ENTIRELY by the
  synthetic fixtures through the CLI's `AXIOM_GITHUB_FIXTURES` hook
  (no live network): `observe-github` on the synthetic PR exits 0
  with `:observation/status :complete`, the exact base/head SHAs, all
  5 collections `:pagination/complete true`, both file pages
  enumerated, and `:trust/provider-observed`; `check-pr` exits 0 with
  3/3 gates `:pass`, advisory can-merge `:yes` with
  `:advisory-only true`, evidence links bound to the exact SHAs; exit
  4 on invalid input (missing `--pr`, malformed slug, bad PR number);
  exit 5 on fixture-mode pagination failure
  (`:observation/incomplete` naming `:changes` page 2); a ledger is
  seeded with a fixture observation through the 0002 append path and
  `replay` shows the `:github-observation` entry with the recorded
  head SHA, event id and `:trust/provider-observed`. The
  0001/0002/0003 gates and exit contracts are unchanged.

Evidence:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **137 tests,
  1651 assertions, 0 failures, 0 errors** — up from the Slice 3
  baseline (133 tests, 1627 assertions). All 0001/0002/0003 CLI gates
  unchanged and passing (evaluate allow 0 / missing 3 / stale 3 /
  failed 2; status/next/explain exit 0; replay/export-bundle 0/4/5;
  ledger tamper gate exit 5; observe-git/digest/run 0/4/5). The 0004
  gates print: observe-github complete with exact SHAs and 5/5
  collections complete; check-pr 3/3 pass with advisory `:yes` and
  evidence links; exits 4/5 as specified; replay shows the recorded
  GitHub observation.
- Zero network in the check path: `grep -rln "java.net.http"
  test/ scripts/` is empty; no socket API references in `test/`
  (enforced by the new static test); every 0004 gate sets
  `AXIOM_GITHUB_FIXTURES` and `env -u AXIOM_GITHUB_TOKEN`, so the CLI
  never reaches the real network fetch.
- Remote CI on the branch (GitHub Actions run 35538755352,
  `offline-kernel` workflow): success from a clean checkout.

Tasks: T4 is now checked (its consumers — the CLI `check-pr` and the
ledger replay path — landed in slices 3–4). T7 and T8 are complete.
Spec 0004 is **Verified** (implementation-complete); no M3 milestone
or Axiom v1 acceptance is claimed from this spec alone — milestone
gates belong to the design's M3 gate review.

## Limits and deferred work

- Test evidence is bounded (synthetic fixtures), not a formal proof or
  production trust attestation.
- This spec produces no `:trust/remote-ci` mark; that mark belongs to
  the M4 trusted evaluation workflow / GitHub App path.
- No live network access is required or used by the test suite;
  provider interactions are tested against recorded synthetic fixtures.
- Check publication, merge and enforcement are M4; agent execution,
  action dispatch, leases and the action outbox are M4/M5; all are out
  of scope here.
- Self-hosting is planned with independent evaluator/policy promotion
  gates; Axiom did not drive this spec run.
- The network adapter (`axiom.adapters.github`, T2) and token/producer
  identity plumbing (T3) landed in Slice 2 above; CLI (T5) and ledger
  wiring (T6) landed in Slice 3 above; the pure `check-pr` (T4),
  adversarial fixture tests (T7) and 0004 `scripts/check` gates (T8)
  landed in Slice 4 above. Spec 0004 is Verified.

## Open spec-compliance question (not a code change)

- R7 writes `observe-github`'s `--pr` flag as `[--pr N]` (optional),
  but the implementation requires `--pr` (exit 4 when missing) because
  the 0004 observation schema and adapter (T1–T4) are PR-centric — a
  repo-at-SHA observation without a PR has no defined observation
  shape. The behavior was recorded as deferred in Slice 3 and is
  re-recorded here as an open question, not papered over: either the
  spec needs an amendment making `--pr` required (documenting
  repo-at-SHA observation as out of scope), or a future slice must
  define and implement the repo-at-SHA observation shape. Changing an
  accepted spec's contract needs a spec amendment, not a code tweak,
  so the behavior is intentionally unchanged in this slice.
