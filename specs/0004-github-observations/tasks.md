# Tasks and acceptance gates

- [x] T1: Implement `axiom.github` (pure port): GitHub observation
  schema (`:observation/kind :github-observation`), the pagination data
  model (collection + completeness marker), identity rules (repo, PR,
  fork, workflow run, job, attempt, artifact), trust marks
  (`:trust/provider-observed` / `:trust/provider-authenticated`, never
  `:trust/remote-ci`), and strict validation via the 0001 EDN schemas.
  No I/O; no new production dependencies. Check conclusion vocabulary
  maps unknown values to `:unknown`, never to success.
  (Landed 2026-09-20 in Slice 1, branch `feat/0004-github-port`:
  `src/axiom/github.clj` + `test/axiom/github_test.clj`, synthetic
  fixtures only; `./scripts/check` 92 tests / 1427 assertions, 0
  failures.)
- [x] T2: Implement `axiom.adapters.github`: the only namespace touching
  the network, via `java.net.http.HttpClient` with declared timeouts,
  user agent and API version header. GET requests only — no code path
  for POST/PUT/PATCH/DELETE. Pagination to the end of every
  collection with completeness markers; bounded retries with backoff
  honoring `Retry-After` / `X-RateLimit-Reset`; 401/403/404 terminal
  with named reasons; bounded ETag cache keyed by
  (repository, resource, ETag), invalidated on base/head change (stale
  reuse is an operational failure). The fetch function is injectable so
  tests run against synthetic fixtures with no network.
  (Landed 2026-09-20 in Slice 2, branch `feat/0004-github-adapter`:
  `src/axiom/adapters/github.clj` — single GET request constructor,
  JDK HttpClient, no new production dependencies; Link pagination with
  per-collection completeness markers; mid-list page failure after
  bounded retries yields `:observation/incomplete` naming collection,
  page and retry bound, never a partial list; 429/5xx bounded retries
  honoring `Retry-After` and `X-RateLimit-Reset`; 401/403/404 terminal
  operational naming status and resource; strict per-field payload
  validation (unknown file statuses/PR states/review states are
  operational, offending field named; unknown check conclusions map to
  `:unknown`); bounded ETag cache keyed by [owner repo resource url]
  with base/head scoping — a 304 for a stale entry is operational;
  provenance records distinct requested URLs, per-URL ETags,
  rate-limit state and the declared client config.
  `test/axiom/adapters_github_test.clj` — 30 tests, synthetic fixtures
  only (invented `synth-org/synth-repo`, 40-hex SHAs, `synth-*` logins),
  zero network; `./scripts/check` 122 tests / 1527 assertions, 0
  failures.)
- [x] T3: Implement producer and workflow identity validation: token
  identity resolution (from `AXIOM_GITHUB_TOKEN` or `--token-file`;
  raw tokens never accepted as CLI arguments), authenticated vs
  anonymous trust marks, forged-producer rejection (`:invalid`),
  workflow/run identity consistency checks (name, path, event, actor,
  run ID, attempt) with inconsistencies as named operational failures.
  (Landed 2026-09-20 in Slice 2, same branch: credential resolved
  out-of-band only — env map defaulting to the real environment, or a
  token file that must be readable; a raw `:token` argument is
  rejected as `:invalid`; token identity resolved once per observation
  via the provider `/user` endpoint (URL derived from the configured
  API base); authenticated observations marked
  `:trust/provider-authenticated`, anonymous ones
  `:trust/provider-observed`; producer claims contradicting the
  resolved identity fail validation as `:invalid` (forged producer);
  workflow/run/job identity checks — unique run IDs, unique ascending
  attempt numbers, job/run association, run conclusion agreeing with
  the latest attempt, job conclusion agreeing with the selected
  attempt — with inconsistencies as named operational failures. A
  passing job inside a failing run is accepted (job conclusions are
  not required to equal the run aggregate). Honest limits: the
  task text's `--token-file` CLI flag belongs to T5 — the CLI does
  not exist yet, so the adapter accepts `:token-file` as an option
  and rejects raw token arguments; workflow name/path/event/actor
  have no second authoritative source, so their "consistency" is
  shape validation plus the run/job/attempt cross-checks above.)
- [ ] T4: Implement `axiom.github/check-pr` (pure): advisory evaluation
  over validated observations into per-gate outcomes bound to exact
  base/head SHAs with exact evidence links; `can-merge` as the
  advisory summary (not enforcement, not a merge, not a published
  check); stale reports (`:stale` when SHAs move, never silently
  current); explicit selection rules for matrix jobs and attempts;
  skipped required jobs, failed reruns, unknown conclusions and
  dismissed reviews make obligations `:unknown`, never allow; fork
  ambiguity handled by always carrying the head repo owner/name.
- [x] T5: Implement `axiom.cli observe-github` and `axiom.cli check-pr`:
  thin adapters over the pure paths; EDN reports; exit 0/4/5 contract
  per R7; both read-only — never mutate provider state, never create,
  migrate or write ledger files. (Slice 3, 2026-09-20: done. Honest
  limit: `observe-github` requires `--pr` in this slice — the 0004
  observation schema (T1–T4) is PR-centric, so repo-at-SHA observation
  without a PR is not supported; R7's `[--pr N]` optional syntax is
  recorded as deferred. `check-pr` uses the default generic gates
  `:pr-identity`, one required approval, `:merge-state`; pure consumers
  can call `axiom.github/check-pr` with explicit gates including
  `:required-checks`.)
- [x] T6: Wire GitHub observations into the ledger: `:observation`
  payload records with `:observation/kind :github-observation`,
  validated by the 0003 schema plus the new kind's exact shape; append
  through the 0002 path (hash-chained, deduplicated, replay-ordered,
  snapshot-covered, bundle-included); `replay` shows provider
  observations; define the synthetic fixture format so fixture-recorded
  decisions replay offline byte-identically. (Slice 3, 2026-09-20:
  done. `ledger/record-observation` dispatches on `:observation/kind`:
  `:git-observation` keeps `:trust/local-diagnostic`, `:github-observation`
  accepts only `:trust/provider-observed` / `:trust/provider-authenticated`;
  `:trust/remote-ci` and unknown kinds are rejected. No `check-pr`
  report is persisted as a separate ledger decision — the advisory
  report is recomputed byte-identically from replayed observations.)
- [ ] T7: Adversarial and boundary tests: pagination failure mid-list,
  stale cache after base/head movement, rate-limit exhaustion, 403
  permission failure, unknown check conclusions, skipped required
  jobs, failed rerun selection, dismissed review on a moved head,
  fork head identity, matrix expansion selection, expired artifacts,
  forged producer, malformed provider payloads. Recorded fixture
  responses reproduce decisions offline (no live network in tests).
- [ ] T8: Verification and `scripts/check` gates: extend `scripts/check`
  with 0004 gates driven entirely by synthetic recorded fixtures (no
  live network): observe a synthetic PR fixture to the end of
  pagination, assert completeness markers and exact SHAs, run
  `check-pr` and assert the advisory outcomes with evidence links,
  assert exit 4 on invalid input and 5 on fixture-mode pagination
  failure, seed a ledger with a fixture observation and assert replay
  shows it; the 0001/0002/0003 exit contracts unchanged.
