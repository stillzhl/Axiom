# Design

Spec 0004 covers the design's M3 item list under the existing pure kernel
and the 0002/0003 infrastructure: read-only, authenticated observation of
GitHub repositories (PR identity, changed files, runs, jobs, attempts,
approvals, artifacts), `check-pr` and advisory `can-merge` with exact
evidence links. The 0001 evaluation semantics, the 0002 ledger semantics
and the 0003 observation schemas are unchanged; this spec adds a provider
observation source that feeds them.

## Ports and adapters

```
axiom.github            (pure port: observation schema, pagination data model,
                         identity rules, trust marks, check-pr evaluation)
axiom.adapters.github   (adapter: HTTP via java.net.http.HttpClient; pagination,
                         retries, ETag cache; the only network-touching namespace)
axiom.store             (adapter: unchanged; owns the ledger the observations feed)
axiom.cli               (thin: observe-github / check-pr, exit codes)
```

`axiom.github` defines the observation schema, the pagination data model,
the identity and trust rules as data, and validates adapter-produced maps
with the strict 0001 EDN schemas; invalid observations are rejected before
they can enter the ledger or the reducer. `axiom.adapters.github` is the
only namespace that opens network connections; it treats all provider
payloads as untrusted text until validated. No HTTP client library
dependency is introduced — the adapter boundary is JDK
`java.net.http.HttpClient`, and its configuration (timeouts, user agent,
API version header) is recorded in every observation's provenance.

## Observation model

A GitHub observation is an `:observation` record with `:observation/kind
:github-observation`, following the 0003 observation shape:

- `:observation/producer` — `{:producer/id "axiom-github-observer"
  :producer/authenticated? bool :github/login <token identity or nil>}`.
- `:observation/subject` — repository identity `{:github/owner
  :github/repo}`, PR identity `{:github/pr N}`, base and head commit
  SHAs, and fork information (`:github/head-repo` owner/name whenever
  the head lives in a fork).
- `:observation/value` — the PR record (title, state, draft flag, merge
  state), the complete changed-file list (typed entries: added,
  modified, removed, renamed with old/new paths — the same change-entry
  vocabulary as the 0003 local observation), workflow runs with their
  jobs, attempts and matrix axes, review approvals, and artifact
  references (name, digest when the provider supplies one, expiry).
- `:observation/provenance` — exact API URLs requested, response ETags,
  the rate-limit state observed, HTTP client configuration.
- `:observation/scope` — the repository, PR number and the revisions
  compared.

## Pagination and completeness

Paginated collections are enumerated by following the provider's `Link`
`rel="next"` headers to the end. Each collection carries
`{:pagination/complete true :pagination/pages n}`. If any page request
fails after bounded retries, the collection is marked incomplete and the
whole observation becomes `:observation/incomplete` with the failing
collection and page named — never a silently partial list. This satisfies
the M3 gate: missing API pages never become success.

## Retries, rate limits, errors

- 429 / transient 5xx: bounded retries (declared max attempts and base
  backoff in the adapter configuration), honoring `Retry-After` and
  `X-RateLimit-Reset` when present. Exhaustion is an operational failure
  naming the bound.
- 401/403/404: terminal — reported as `:operational` with the status and
  the resource named. A 404 on a PR or repo is "unknown PR/repo" only
  when the request was well-formed; it never degrades into a partial
  success.
- Malformed provider payloads (unexpected JSON shape, unknown enum
  values): operational failure with the offending field named, never
  silently normalized. Unknown check conclusions map to `:unknown` —
  never to success.

## Identity and trust

Credential: `AXIOM_GITHUB_TOKEN` or `--token-file PATH`; raw tokens are
never CLI arguments. When authenticated, the adapter resolves the token
identity once per observation (the account the token belongs to) and
records it; the record is marked `:trust/provider-authenticated`.
Without a credential the observation is marked `:trust/provider-observed`.
A producer claim contradicting the resolved identity fails validation as
`:invalid` (forged producer). Workflow identity (workflow name and path,
triggering event, actor, run ID, attempt number) is validated for
internal consistency; inconsistencies are operational failures with the
field named.

The `:trust/remote-ci` mark is reserved for the M4 trusted evaluation
workflow / GitHub App path. This spec's observations can never carry it:
the port rejects any observation whose trust is not one of
`:trust/provider-observed` or `:trust/provider-authenticated`.

## Matrix, attempts, approvals

- Matrix: each job records its matrix axes (`{:matrix/axes {...}}`); a
  "workflow result" is defined by explicit selection — all required
  matrix jobs, named axes, or latest — and the selection rule is recorded
  in the report.
- Attempts: every attempt is enumerated; latest-attempt semantics select
  the current attempt, but the failed attempts remain in the record. A
  rerun never rewrites history.
- Approvals: reviews are recorded with state, author and commit SHA;
  dismissed reviews and reviews on a different head SHA do not satisfy
  approval obligations. Skipped required jobs, failed reruns, unknown
  conclusions and dismissed reviews make the dependent obligation
  `:unknown`, never allow.

## check-pr and advisory can-merge

`axiom.github/check-pr` is a pure function over validated observations:
it emits per-gate outcomes (`:pass` / `:fail` / `:unknown`), each bound
to the exact base and head SHAs observed and carrying exact evidence
links (the API URLs and artifact references the outcome was read from).
`can-merge` is the same report's advisory summary — not enforcement, not
a merge, not a published check. If a later observation shows different
base/head SHAs, any prior report is `:stale`. The design's M3 gate
("base/head changes ... never become success") is enforced here: a
stale report cannot be promoted to current.

## Ledger integration

GitHub observations flow through the 0002 append path as `:observation`
payload records (`:observation/kind :github-observation`), hash-chained,
deduplicated by `event_id`/`dedup_key`, replay-ordered by sequence,
covered by snapshots and included in export bundles — exactly like the
0003 local observations. `replay` shows the provider observations behind
each decision.

## Fixtures and offline reproduction

The port defines a fixture format: EDN snapshots of provider responses
keyed by request URL, entirely synthetic (invented repositories, SHAs,
logins). The adapter accepts an injected fetch function, so tests run
the full pagination/retry/identity logic against fixtures with no
network. A decision recorded from fixture observations replays
byte-identically offline — satisfying the design's "recorded provider
fixtures reproduce decisions offline" gate. `scripts/check` never
touches the network.

## CLI

- `observe-github --repo OWNER/NAME [--pr N] [--sha SHA]
  [--token-file PATH]`: read-only provider observation report (EDN),
  exit 0 valid / 4 invalid input / 5 operational failure (network/API
  errors, incomplete pagination, rate-limit exhaustion, stale cache).
  Never mutates provider state; never creates, migrates or writes
  ledger files.
- `check-pr --repo OWNER/NAME --pr N [--token-file PATH]`: advisory
  report (EDN) with per-gate outcomes, SHAs and evidence links, exit
  0/4/5. Advisory only.

## Out of scope (explicit)

Check publication and merge (M4); any enforcement; provider API write
calls (no POST/PUT/PATCH/DELETE code paths in the adapter); agent
execution, action dispatch, leases, action outbox (M4/M5); worker
isolation; signatures and tamper-proofing; recursion into untrusted
payload fields; any change to 0001/0002/0003 decision bytes, exit
contracts or schemas beyond the additive `:github-observation` payload
kind; real credentials or real repository identities in fixtures.
