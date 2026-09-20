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
