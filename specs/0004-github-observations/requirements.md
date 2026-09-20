# 0004 — GitHub observations (read-only authenticated provider observation)

Status: Accepted for implementation; verification pending.

Authority: the repository owner's 2026-09-20 instruction, "Start to implement
axiom", with the attached design plan, authorizes bounded slices. The owner's
2026-09-20 autopilot instruction authorizes continued bounded slices under the
spec-driven sequence (requirements → design → tasks → implementation →
verification), one focused branch/PR per slice. This records implementation
scope, not an independent security review or v1 acceptance. The larger design
remains proposed.

## Spec naming and scope decision (recorded 2026-09-20)

Specs `0002-durable-ledger` and `0003-local-observations` (both Verified)
covered the design's M2 item list: the SQLite event store with replay and
bundles, the local Git adapter, artifact digesting with bounded retention
metadata, and the local diagnostic verification runner. This spec covers the
design's M3 item list: read-only, authenticated observation of GitHub
repositories — PR identity, full changed-file lists, workflow runs, jobs,
attempts, approvals and relevant artifacts — plus `check-pr` and advisory
`can-merge` with exact evidence links. This spec does NOT cover merge,
check publication or any enforcement (M4), agent execution or the action
outbox (M4/M5), or the M3 milestone gate; milestone acceptance belongs to
the design's M3 gate review.

## Repository boundary (owner instruction, 2026-09-20)

The Axiom repository stays consumer-agnostic. Consumer-specific contracts,
policies, fixtures, scenarios and verification evidence belong in the consumer
repository (e.g. HomeKV), never here. All fixtures and examples in this spec
are synthetic and invented for tests. No live GitHub credentials, tokens or
real repository identities may appear in this repo's fixtures or evidence.

## Requirements

- R1: Read-only GitHub observation. A pure `axiom.github` port and an
  explicit `axiom.adapters.github` adapter observe GitHub repositories and
  pull requests through the provider API and return validated observation
  maps, never mutating provider state. Each observation carries the
  design's Observation fields: producer identity (`:producer/id
  "axiom-github-observer"`, `:producer/authenticated?` true or false, and
  the resolved token identity when authenticated), subject identity
  (repository owner/name, PR number, base and head commit SHAs, fork
  information), value (PR identity, the complete changed-file list,
  workflow runs with jobs, attempts, matrix expansion, approvals and
  relevant artifact references), provenance (exact API URLs requested,
  response ETags, rate-limit state observed) and collection scope (the
  repository, PR number and revisions compared). The adapter performs GET
  requests only; it structurally cannot issue mutations — there is no
  code path for POST/PUT/PATCH/DELETE against the provider API.
- R2: Complete pagination with completeness markers. Every paginated
  collection (changed files, runs, jobs, artifacts) is enumerated to the
  end; each collection carries a completeness marker
  (`:pagination/complete true` plus the page count observed). A missing
  or failed page makes the observation `:observation/incomplete` with the
  failing collection and page named — never a silently partial list.
  Rate-limit and error handling: HTTP 429 and transient 5xx are retried a
  bounded number of times with backoff, honoring `Retry-After` /
  `X-RateLimit-Reset` when present; HTTP 401/403/404 are terminal and
  reported as operational failures with the reason named, never retried
  silently into a partial result. Bounded ETag caching is allowed: cached
  pages are keyed by (repository, resource, ETag) and are invalidated
  when the observed base or head SHA changes; a stale cache hit after
  base/head movement is an operational failure, not a silent reuse.
- R3: Authenticated producer and workflow identity. The credential is
  supplied out-of-band: environment variable `AXIOM_GITHUB_TOKEN` or
  `--token-file PATH`; a raw token is never accepted as a CLI argument
  (it would leak into process listings). When authenticated, the adapter
  resolves the token identity (the account it belongs to) and records it
  in the observation; when no credential is configured, observation runs
  anonymously and is marked accordingly. Workflow and run identity is
  validated: workflow name and path, triggering event, actor, run ID and
  attempt number are checked for internal consistency; a producer claim
  that contradicts the resolved identity is rejected as `:invalid`
  (forged producer), never normalized.
- R4: Matrix and rerun selection semantics. Matrix jobs are expanded into
  their full job identities with matrix axes recorded per job; selecting
  "the" result of a matrix requires explicit selection rules (all
  required matrix jobs, named axes, or latest). Attempts are enumerated
  with latest-attempt semantics: a rerun does not erase the failed
  attempt's record, and the selection of which attempt counts is explicit
  and recorded. Skipped required jobs, failed reruns, unknown check
  conclusions and dismissed reviews can never satisfy an evidence
  obligation — the dependent obligation becomes `:unknown`, never
  allowed.
- R5: `check-pr` and advisory `can-merge` with exact evidence links. A
  pure evaluator consumes collected observations and emits a `check-pr`
  report: per-gate outcomes bound to exact base/head SHAs, with exact
  evidence links (the API URLs and the SHAs/artifacts they were read
  from). `can-merge` is advisory only: it is not enforcement, not a
  merge, and not a check publication. A report whose base or head SHA no
  longer matches a fresh observation is `:stale` — never silently
  current. Fork ambiguity (PRs from forks, same head across repos) is
  handled explicitly: subject identity always includes the fork's
  owner/name, and observations never conflate fork and upstream refs.
- R6: Observations feed the ledger as events. GitHub observations are
  recorded through the 0002 append path as `:observation` payload records
  with `:observation/kind :github-observation`, validated by the strict
  0003 schema plus the new kind's exact shape. Recorded provider
  fixtures (EDN snapshots of provider responses, synthetic) let a
  decision recorded from fixtures reproduce offline byte-identically;
  the fixture format is part of the port. Unknown or malformed
  observations are rejected by validation and cannot admit actions; a
  missing or incomplete observation makes the dependent obligation
  `:unknown`, never allowed.
- R7: CLI surface, read-only diagnostics. `axiom.cli observe-github
  --repo OWNER/NAME [--pr N] [--sha SHA] [--token-file PATH]` emits the
  EDN GitHub observation report; `axiom.cli check-pr --repo OWNER/NAME
  --pr N` emits the advisory report. Both are read-only: they never
  mutate provider state and never create, migrate or write ledger files
  (recording an observation is done through the 0002 append API, not the
  CLI). Exit contract matches 0002/0003: 0 on a valid report, 4 on
  invalid input (malformed repo slug, unknown PR, bad arguments,
  unreadable token file), 5 on operational failure (network failure,
  API error, rate-limit exhaustion, incomplete pagination, stale cache).
- R8: Core purity and adapter boundary. The evaluation core
  (`axiom.model`, `axiom.contract`, `axiom.world`, `axiom.prover`,
  `axiom.nomos`, `axiom.ledger`, `axiom.git`, `axiom.runner`) stays pure
  and side-effect-free, with no production dependencies beyond Clojure.
  Network access lives only in `axiom.adapters.github` behind the pure
  `axiom.github` port, using `java.net.http.HttpClient` (JDK standard,
  no new production dependency). No other namespace opens sockets,
  spawns processes or touches the database file. Unknown or malformed
  required inputs cannot admit actions; malformed provider payloads that
  fail validation are operational failures, never silently normalized.
- R9: Honest trust levels. Anonymous observations are marked
  `:trust/provider-observed`; authenticated observations with validated
  identities are marked `:trust/provider-authenticated`. Neither mark
  may claim `:trust/remote-ci` — that mark belongs to the M4 trusted
  evaluation workflow / GitHub App path and is explicitly not produced
  here. Observations authorize nothing; an allow result is not
  authorization to execute or merge.

## Explicit boundaries

No check publication and no merge capability of any kind (M4). No
enforcement of any kind: this spec observes and advises only. No write
calls to the provider API (no POST/PUT/PATCH/DELETE code paths in the
adapter). No agent execution, action dispatch, leases or action outbox
(M4/M5). No worker isolation claims. No signatures or tamper-proofing:
the local ledger remains tamper-evident, not tamper-proof (0002 R9).
No live-network tests in `scripts/check` — provider interactions are
tested against recorded synthetic fixtures only; live network access is
never required to run the test suite. No change to 0001/0002/0003
decision bytes, exit contracts or the 0002 schema-1/2/3 semantics beyond
the additive `:github-observation` payload kind. Test evidence is
bounded observation, not formal proof. This spec does not make
provider-observed evidence admissible as trusted remote CI.
