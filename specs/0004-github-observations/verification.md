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
- The network adapter (`axiom.adapters.github`, T2), token/producer
  identity plumbing (T3), CLI (T5), ledger wiring (T6), adversarial
  fixture tests (T7), and 0004 `scripts/check` gates (T8) are not part
  of this slice.
