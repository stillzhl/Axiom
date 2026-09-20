# Tasks and acceptance gates

- [ ] T1: Implement `axiom.github` (pure port): GitHub observation
  schema (`:observation/kind :github-observation`), the pagination data
  model (collection + completeness marker), identity rules (repo, PR,
  fork, workflow run, job, attempt, artifact), trust marks
  (`:trust/provider-observed` / `:trust/provider-authenticated`, never
  `:trust/remote-ci`), and strict validation via the 0001 EDN schemas.
  No I/O; no new production dependencies. Check conclusion vocabulary
  maps unknown values to `:unknown`, never to success.
- [ ] T2: Implement `axiom.adapters.github`: the only namespace touching
  the network, via `java.net.http.HttpClient` with declared timeouts,
  user agent and API version header. GET requests only — no code path
  for POST/PUT/PATCH/DELETE. Pagination to the end of every
  collection with completeness markers; bounded retries with backoff
  honoring `Retry-After` / `X-RateLimit-Reset`; 401/403/404 terminal
  with named reasons; bounded ETag cache keyed by
  (repository, resource, ETag), invalidated on base/head change (stale
  reuse is an operational failure). The fetch function is injectable so
  tests run against synthetic fixtures with no network.
- [ ] T3: Implement producer and workflow identity validation: token
  identity resolution (from `AXIOM_GITHUB_TOKEN` or `--token-file`;
  raw tokens never accepted as CLI arguments), authenticated vs
  anonymous trust marks, forged-producer rejection (`:invalid`),
  workflow/run identity consistency checks (name, path, event, actor,
  run ID, attempt) with inconsistencies as named operational failures.
- [ ] T4: Implement `axiom.github/check-pr` (pure): advisory evaluation
  over validated observations into per-gate outcomes bound to exact
  base/head SHAs with exact evidence links; `can-merge` as the
  advisory summary (not enforcement, not a merge, not a published
  check); stale reports (`:stale` when SHAs move, never silently
  current); explicit selection rules for matrix jobs and attempts;
  skipped required jobs, failed reruns, unknown conclusions and
  dismissed reviews make obligations `:unknown`, never allow; fork
  ambiguity handled by always carrying the head repo owner/name.
- [ ] T5: Implement `axiom.cli observe-github` and `axiom.cli check-pr`:
  thin adapters over the pure paths; EDN reports; exit 0/4/5 contract
  per R7; both read-only — never mutate provider state, never create,
  migrate or write ledger files.
- [ ] T6: Wire GitHub observations into the ledger: `:observation`
  payload records with `:observation/kind :github-observation`,
  validated by the 0003 schema plus the new kind's exact shape; append
  through the 0002 path (hash-chained, deduplicated, replay-ordered,
  snapshot-covered, bundle-included); `replay` shows provider
  observations; define the synthetic fixture format so fixture-recorded
  decisions replay offline byte-identically.
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
