# Verification record

State: spec authored and Accepted 2026-09-20; implementation pending. No
M3 milestone or Axiom v1 acceptance is claimed from this spec alone;
milestone gates belong to the design's M3 gate review.

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
  identity plumbing (T3) landed in Slice 2 above. CLI (T5), ledger
  wiring (T6), adversarial fixture tests (T7), and 0004
  `scripts/check` gates (T8) remain.
