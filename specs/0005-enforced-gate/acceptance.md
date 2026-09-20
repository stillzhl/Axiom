# Acceptance gates for spec 0005

This file records the acceptance criteria that make spec 0005 "Accepted"
(spec-complete) versus "Verified" (implementation-complete). The gates were
recorded before any implementation, per the repo's contributor instructions.

## Spec acceptance (this slice — met 2026-09-20)

- [x] requirements.md, design.md, tasks.md, verification.md and this
  acceptance.md are written and internally consistent.
- [x] Scope decision recorded: 0005 is the M4 enforced-gate slice
  (trusted evaluation workflow / GitHub App path, approved-policy
  loading independent of candidate content, check publication bound to
  candidate and evaluator identity, protected branch / merge-queue
  integration with merge-group evaluation, governance-change handling
  with explicit authorization separation). Agent execution, action
  dispatch, leases, the action outbox and isolated workers are M5;
  Axiom-driven self-hosting and its policy promotion gates are M5;
  automatic merge is out of scope entirely — an allow decision is not
  a merge instruction.
- [x] Authorization model explicit: authorizer (repository owner),
  evaluator (trusted workflow/App), candidate (untrusted) are distinct
  roles in the data model; governance events carry authorizer identity
  and digests; gate runs pin their policy approval; untrusted inputs
  are quarantined from policy loading and evaluator configuration.
- [x] Core-purity and adapter-boundary rules stated: the 0001–0004
  core is unchanged; provider mutation lives only in
  `axiom.adapters.checks` behind the pure `axiom.gate` port, selected
  only after the R8 capability check, via JDK
  `java.net.http.HttpClient` (no new production dependency); the
  adapter has no code paths for any provider mutation other than
  check-run publication; the 0001–0004 decision bytes and exit
  contracts are explicitly preserved.
- [x] Consumer-agnostic rule restated: all fixtures synthetic; nothing
  HomeKV-specific; no live credentials or real repository identities
  in fixtures or evidence.
- [x] Honest limits recorded: `:trust/remote-ci` producible only by
  the trusted evaluator path, never promoted from earlier marks;
  advisory mode is reported, never claimed as enforcement;
  administrator bypasses are recorded as bypassed, never as passed;
  test evidence will be bounded synthetic observation, not formal
  proof and not a production trust attestation; the spec does not
  claim the gate is un-bypassable in production.
- [x] `./scripts/check` passes on this docs-only change (no implementation
  claims made from it).

Spec status after this slice: **Accepted** — requirements, design, tasks and
acceptance gates are recorded. Verification is pending implementation.

## Implementation acceptance (pending)

- [ ] `./scripts/check` is green on Temurin 17.0.20 / Clojure 1.12.0
  with the 0005 gates: 0005 gates evaluate synthetic candidates
  against approved-policy fixtures, assert allow/deny/defer with named
  reasons, publish through the fake Checks API and assert identity
  binding and idempotency, and exercise the advisory-mode fallback;
  the 0001–0004 exit contracts are unchanged; `git diff --check`
  clean. Remote CI: green from a clean checkout. No live network
  access in the check gates.
- [ ] All five anti-bypass classes are denied in tests with named
  reasons: policy edit in candidate, verifier replacement without
  governance approval, renamed check, omitted required verification,
  stale success. Each bypass attempt yields deny/defer, never allow.
- [ ] Approved-policy loading rejects unapproved digests and
  candidate-branch policy sources structurally; a run that cannot name
  its policy approval is `:invalid`.
- [ ] `:trust/remote-ci` appears only with a valid evaluator-bound
  publication reference and a current trusted observation; earlier
  marks are never promoted; forged marks are `:invalid` at ledger
  validation.
- [ ] `publish-check` publishes only after a passing capability check;
  in advisory mode it performs zero provider writes and reports
  advisory mode explicitly. Administrator bypasses are recorded as
  `:governance/admin-bypass` and reported as `:bypassed`.
- [ ] Gate evaluations, publications and governance events append
  through the 0002 path; snapshots remain replay-equivalent; replay of
  a prefix reproduces recorded gate decisions verbatim with their
  policy digests.
- [ ] verification.md records exact commands, results and remaining
  limits.

Spec status: **Accepted** (2026-09-20) — implementation pending.
