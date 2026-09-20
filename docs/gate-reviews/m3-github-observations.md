# M3 gate review — GitHub and CI observation

Reviewed: 2026-09-20. Authority: the owner's standing Axiom autopilot
instruction; this is a docs-only milestone audit, not an independent
security review. Source: the proposed design plan, "M3 — GitHub and CI
observation" (Implement list + Gate). Candidate evidence: spec
`0004-github-observations`, **Verified** (implementation-complete) on
main `afcda2f` (slices PR #13–#17).

Rule: this review audits the spec against the design's M3 criteria. It
does not edit any spec's Verified claims and does not claim anything
beyond M3 (no v1, no M0/M1/M4 acceptance).

## Implement list audit

- **Read-only collection of PR identity, full changed-file lists, runs,
  jobs, attempts, approvals, and relevant artifacts** — satisfied. Spec
  0004 R1: pure `axiom.github` port + `axiom.adapters.github` adapter,
  GET-only (structurally no POST/PUT/PATCH/DELETE code paths); every
  observation carries producer identity, subject identity (repo, PR,
  base/head SHAs, fork info), value (changed files, runs with jobs,
  attempts, matrix expansion, approvals, artifact references),
  provenance (exact API URLs, ETags, rate-limit state) and collection
  scope. Evidence: 0004 acceptance.md (`adapter-is-get-only`,
  `forged-producer`, `trust-marks`, `matrix-selection`,
  `latest-selection`, `fork-identity`, `check-pr-approvals`,
  `expired-artifacts-recorded-intact`); local check 137 tests / 1651
  assertions, branch CI run 35538755352 (`offline-kernel`) success,
  post-merge main CI 35539278035 success.
- **Complete pagination and observation completeness markers** —
  satisfied. Spec 0004 R2: every paginated collection enumerated to the
  end; each carries `:pagination/complete true` plus the page count; a
  missing or failed page makes the observation
  `:observation/incomplete` naming the collection and page, never a
  silently partial list. Evidence: `mid-list-page-failure-is-incomplete`,
  `jobs-page-failure-names-run`; the 0004 check gates observe a
  synthetic PR fixture to the end of pagination and assert
  completeness markers and exact SHAs.
- **Rate-limit/error handling, bounded retries, and cache
  invalidation** — satisfied. Spec 0004 R2: bounded retries with
  backoff honoring `Retry-After` / `X-RateLimit-Reset`; 401/403/404
  terminal with named reasons; rate-limit exhaustion is an operational
  failure naming the bound; bounded ETag cache (keyed by owner/repo/
  resource/url, size 128) invalidated when observed base or head SHA
  changes, and a stale hit after SHA movement is an operational
  failure. Evidence: `retry-429-honors-retry-after`,
  `retry-honors-rate-limit-reset`,
  `rate-limit-exhaustion-names-bound`,
  `single-resource-retry-exhaustion-is-operational`,
  `terminal-404-unknown-pr`, `terminal-403-runs`,
  `stale-etag-cache-hit-is-operational`, `etag-304-reuses-cache-under-same-shas`;
  the 0004 check gates assert exit 5 on fixture-mode pagination
  failure.
- **Producer and workflow identity validation; matrix and rerun
  selection** — satisfied. Spec 0004 R3/R4: token resolved to its
  account identity and recorded; forged producer claims rejected as
  `:invalid`, never normalized; workflow name/path/event/actor/run
  ID/attempt checked for internal consistency; matrix jobs expanded
  with axes and explicit selection rules; attempts enumerated with
  latest-attempt semantics, rerun history preserved. Evidence:
  `authenticated-observation-records-token-identity`,
  `token-file-credential`, `credential-rejected-is-operational`,
  `forged-producer-claims-are-invalid`, `duplicate-run-id-is-operational`,
  `run-conclusion-must-match-latest-attempt`,
  `job-conclusion-must-match-selected-attempt`,
  `job-run-association-mismatch`, `matrix-selection`, `latest-selection`,
  `attempt-identity`, `fork-pr-observation-carries-head-repo`.
- **`check-pr` and advisory `can-merge` with exact evidence links** —
  satisfied. Spec 0004 R5: pure evaluator emits per-gate outcomes bound
  to exact base/head SHAs with exact evidence links (API URLs and the
  SHAs/artifacts read from); `can-merge` advisory only — not
  enforcement, not a merge, not check publication; a report whose SHAs
  no longer match fresh observation is `:stale`, never silently
  current. Evidence: `check-pr-all-green`, `check-pr-failures`,
  `stale-on-sha-move`; the 0004 check gates assert 3/3 gates pass with
  evidence links and exact SHAs.

## Gate audit (design's M3 gate conditions)

- **Missing API pages never become success** — satisfied. A missing or
  failed page makes the observation `:observation/incomplete` naming
  the collection and page; the dependent obligation becomes `:unknown`,
  never allowed (R2/R6). Tested
  (`mid-list-page-failure-is-incomplete`).
- **Permission errors never become success** — satisfied. 401/403 are
  terminal operational failures with the reason named; never retried
  silently into a partial result (R2). Tested (`terminal-403-runs`).
- **Fork ambiguity never becomes success** — satisfied. Subject
  identity always includes the fork's owner/name; observations never
  conflate fork and upstream refs (R5). Tested (`fork-identity`,
  `fork-pr-observation-carries-head-repo`).
- **Expired required artifacts never become success** — satisfied,
  with a scope note. Expired artifacts are recorded intact (expiry as
  epoch seconds, never dropped or normalized); malformed expiry is an
  operational failure naming the field. No code path can treat an
  expired artifact as current, and the shipped generic check-pr gates
  (`:pr-identity`, one required approval, `:merge-state`) never grant
  a pass from artifact data at all. Scope note: artifact-freshness as
  a named obligation is not among the generic default gates; a
  consumer-defined gate must read the recorded expiry itself — the
  honest data for that is always present. Tested
  (`expired-artifacts-recorded-intact`, incl. the malformed-expiry
  operational-failure branch).
- **Base/head changes never become success** — satisfied. A report
  whose base or head SHA no longer matches a fresh observation is
  `:stale`, never silently current (R5). Tested (`stale-on-sha-move`).
- **Skipped required jobs never become success** — satisfied. Skipped
  required jobs make the dependent obligation `:unknown`, never
  allowed (R4). Tested (`check-pr-unknowns`).
- **Unknown check conclusions never become success** — satisfied.
  Unknown check conclusions map to `:unknown`, never success; failed
  reruns and dismissed reviews likewise (R4). Tested
  (`check-pr-unknowns`).
- **Recorded provider fixtures reproduce decisions offline** —
  satisfied. Recorded provider fixtures (synthetic EDN snapshots of
  provider responses) let a decision recorded from fixtures replay
  offline byte-identically; the fixture format is part of the port
  (R6). Tested (`fixture-observations-replay-byte-identically-offline`,
  `github-observation-records-through-append-path`); zero network in
  the check path is enforced by the `no-test-opens-a-socket` static
  test.

## Honest limits (carried from the spec record)

- Spec verification evidence is bounded synthetic observation, not
  formal proof or a production trust attestation.
- Observations are marked `:trust/provider-observed` /
  `:trust/provider-authenticated` and can never claim
  `:trust/remote-ci` (reserved for the M4 trusted evaluation
  workflow); they authorize nothing — advisory only, no execution,
  merge, check publication or enforcement.
- The local ledger remains tamper-evident, not tamper-proof (0002 R9);
  inputs are unauthenticated.
- Open spec-compliance question (recorded in the 0004 verification
  record, not a hidden gap): R7 writes `observe-github`'s `--pr` as
  `[--pr N]` (optional), but the implementation requires `--pr`
  (exit 4 when missing) because the 0004 observation schema and
  adapter are PR-centric — a repo-at-SHA observation without a PR has
  no defined observation shape. Either the spec needs an amendment
  making `--pr` required, or a future slice must define and implement
  the repo-at-SHA observation shape. The behavior is intentionally
  unchanged: changing an accepted spec's contract needs a spec
  amendment, not a code tweak. This review does not treat the open
  question as a gate blocker — the audited behavior (explicit exit 4,
  never silently supported) is the honest form — but the amendment
  should land before M4 builds on the CLI contract.
- Axiom did not drive this spec run; self-hosting is planned with
  independent evaluator/policy promotion gates.

## Verdict

**M3 is COMPLETE.** Every design-listed M3 deliverable and every M3
gate condition is satisfied by Verified spec 0004, with evidence
recorded in its verification record (local check + branch CI +
post-merge main CI). The one open item is the documented `--pr`
spec-compliance question, which is an amendment task, not a missing
deliverable.

Out of scope, deliberately not claimed: M0/M1 milestone acceptance
(their gate reviews were never performed; the deferred M1 items —
source mappings/digests, richer evidence schemas, JSON interchange —
remain named gaps for the M1 review), M4 milestone acceptance (the M4
trusted evaluation workflow / GitHub App path is not designed yet),
and Axiom v1 acceptance.

## Recommended next bounded units

1. **M1 gate review** (docs-only, same pattern as this review): the
   design's M1 gate was never reviewed; this will surface the deferred
   M1 items (source mappings/digests, richer evidence schemas, JSON
   interchange, context/readiness commands) and decide whether M1 can
   be marked complete or needs additional bounded specs.
2. **Spec 0004 `--pr` amendment** (docs-only): decide whether `--pr`
   is required (documenting repo-at-SHA observation as out of scope)
   or define the repo-at-SHA observation shape; fold into the
   requirements/design of the M1 review or a standalone amendment.
3. Only after the M1 review: author the **M4 spec** (enforced
   verification gate / trusted evaluation) to Accepted, per the
   spec-driven sequence.
