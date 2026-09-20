# Acceptance gates for spec 0004

This file records the acceptance criteria that make spec 0004 "Accepted"
(spec-complete) versus "Verified" (implementation-complete). The gates were
recorded before any implementation, per the repo's contributor instructions.

## Spec acceptance (this slice — met 2026-09-20)

- [x] requirements.md, design.md, tasks.md, verification.md and this
  acceptance.md are written and internally consistent.
- [x] Scope decision recorded: 0004 is the M3 provider-observation slice
  (read-only, authenticated GitHub observation; `check-pr` and advisory
  `can-merge` with exact evidence links). Check publication and merge
  are M4; agent execution, action dispatch, leases and the action
  outbox are M4/M5; no enforcement is in scope.
- [x] Core-purity and adapter-boundary rules stated: the 0001/0002/0003
  core is unchanged; network access lives only in
  `axiom.adapters.github` behind the pure `axiom.github` port, via
  JDK `java.net.http.HttpClient` (no new production dependency); the
  adapter has no write-API code paths; the 0001/0002/0003 decision
  bytes and exit contracts are explicitly preserved.
- [x] Consumer-agnostic rule restated: all fixtures synthetic; nothing
  HomeKV-specific; no live credentials or real repository identities in
  fixtures or evidence.
- [x] Honest limits recorded: provider observations are marked
  `:trust/provider-observed` / `:trust/provider-authenticated` and can
  never claim `:trust/remote-ci` (reserved for the M4 trusted
  evaluation workflow); pagination failures are `:observation/incomplete`
  never silently partial; skipped required jobs, failed reruns, unknown
  conclusions and dismissed reviews make obligations `:unknown`, never
  allow; no live-network tests in `scripts/check`; incomplete
  observations cannot admit actions; observations authorize nothing.
- [x] `./scripts/check` passes on this docs-only change (no implementation
  claims made from it).

Spec status after this slice: **Accepted** — requirements, design, tasks and
acceptance gates are recorded. Verification is pending implementation.

## Implementation acceptance (pending)

- [ ] `./scripts/check` is green on Temurin 17.0.20 / Clojure 1.12.0 with
  the 0004 gates: 0004 gates observe a synthetic PR fixture to the end
  of pagination, assert completeness markers and exact SHAs, run
  `check-pr` and assert advisory outcomes with evidence links, seed a
  ledger with a fixture observation and assert replay shows it; the
  0001/0002/0003 exit contracts are unchanged; `git diff --check` clean.
  Remote CI: green from a clean checkout. No live network access in the
  check gates.
- [x] Pagination enumerates every collection to the end with
  completeness markers; a mid-list page failure after bounded retries
  yields `:observation/incomplete` naming the collection and page,
  never a silently partial list. Rate-limit exhaustion is an
  operational failure naming the bound; 401/403/404 are terminal with
  named reasons; malformed provider payloads fail operationally with
  the field named, never silently normalized.
  (Evidence: `mid-list-page-failure-is-incomplete`,
  `jobs-page-failure-names-run`,
  `rate-limit-exhaustion-names-bound`,
  `single-resource-retry-exhaustion-is-operational`,
  `retry-429-honors-retry-after`, `retry-honors-rate-limit-reset`,
  `terminal-404-unknown-pr`, `terminal-403-runs`, `malformed-*`,
  `pr-number-mismatch-is-operational` in
  `test/axiom/adapters_github_test.clj` and
  `test/axiom/github_adversarial_test.clj`; the 0004 check gates
  assert exit 5 on fixture-mode pagination failure.)
- [x] Authenticated observations carry the resolved token identity and
  `:trust/provider-authenticated`; anonymous observations carry
  `:trust/provider-observed`; forged producers are `:invalid`; no
  observation can carry `:trust/remote-ci`. Workflow/run identity
  inconsistencies are named operational failures.
  (Evidence: `authenticated-observation-records-token-identity`,
  `token-file-credential`, `credential-rejected-is-operational`,
  `forged-producer-claims-are-invalid`, `duplicate-run-id-is-operational`,
  `run-conclusion-must-match-latest-attempt`,
  `job-conclusion-must-match-selected-attempt`,
  `job-run-association-mismatch` in
  `test/axiom/adapters_github_test.clj`; `forged-producer`,
  `trust-marks` in `test/axiom/github_test.clj`.)
- [x] Matrix jobs expand with recorded axes and explicit selection
  rules; attempts enumerate with latest-attempt semantics and history
  preserved; fork heads carry the head repo owner/name and are never
  conflated with upstream refs. Skipped required jobs, failed reruns,
  unknown conclusions and dismissed reviews make obligations
  `:unknown`, never allow.
  (Evidence: `matrix-selection`, `latest-selection`, `fork-identity`,
  `check-pr-unknowns`, `check-pr-approvals`, `attempt-identity` in
  `test/axiom/github_test.clj`; `fork-pr-observation-carries-head-repo`
  in `test/axiom/github_adversarial_test.clj`; expired artifacts
  recorded intact in `expired-artifacts-recorded-intact`.)
- [x] `check-pr` binds every outcome to exact base/head SHAs with exact
  evidence links; a report whose SHAs moved is `:stale`, never
  silently current; `can-merge` remains advisory. The adapter performs
  GET requests only; provider state is never mutated.
  (Evidence: `check-pr-all-green`, `check-pr-failures`,
  `stale-on-sha-move` in `test/axiom/github_test.clj`;
  `adapter-is-get-only` in `test/axiom/adapters_github_test.clj`;
  the 0004 check gates assert 3/3 gates pass with evidence links and
  exact SHAs.)
- [x] GitHub observations append through the 0002 path as
  `:observation/kind :github-observation` and replay shows them;
  fixture-recorded decisions replay offline byte-identically.
  (Evidence: `github-observation-records-through-append-path`,
  `github-observation-trust-marks`, `github-observation-dedup-and-replay-cli`,
  `fixture-observations-replay-byte-identically-offline`,
  `github-observations-are-snapshot-covered-and-bundle-included` in
  `test/axiom/github_cli_ledger_test.clj`; the 0004 check gates seed a
  ledger with a fixture observation and assert replay shows it.)
- [x] verification.md records exact commands, results and remaining
  limits. (This slice: branch, test counts, gate output, honest
  limits, and the open `--pr`-required spec-compliance question.)

Spec status: **Accepted** (2026-09-20). No M3 milestone or Axiom v1
acceptance is claimed from this spec alone; milestone gates belong to
the design's M3 gate review.
