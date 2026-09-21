# M4 gate review — Enforced verification gate

Reviewed: 2026-09-20. Authority: the owner's standing Axiom autopilot
instruction; this is a docs-only milestone audit, not an independent
security review. Source: the proposed design plan, "M4 — Enforced
verification gate" (Implement list + Gate). Candidate evidence: spec
`0005-enforced-gate`, **Verified** (implementation-complete) on main
`2fd8050` (slices PR #22–#25).

Rule: this review audits the spec against the design's M4 criteria. It
does not edit any spec's Verified claims and does not claim anything
beyond M4 (no v1, no M5/M6 acceptance).

## Implement list audit

- **Trusted evaluation workflow or dedicated GitHub App path
  appropriate to deployment permissions** — satisfied. Spec 0005
  R5/R7/R8: the evaluator is a deployment-configured trusted identity
  (workflow or GitHub App instance); evaluator identity is bound to
  every evaluation and every published check run; the deployment
  capability check selects the Checks API writer only after
  capability is proven. Which concrete deployment path (workflow vs
  App) is a deployment decision, and the spec correctly leaves the
  production trust establishment to the deployment — what the spec
  proves is the predicate logic of evaluator binding. Evidence:
  0005 verification.md slices 1–3 (`constructable?` pure refusal
  predicate, evaluator-bound publication); test
  `axiom.checks-test` (12 tests, construction refusal, evaluator
  mismatch failures, identity binding never silently corrected).
- **Approved-policy loading independent of candidate content** —
  satisfied. Spec 0005 R1: policy loaded from a digest-pinned source
  with approval events in the ledger (`:governance/policy-approved`),
  structurally rejected when the source descriptor points at the
  candidate ref; a digest without a recorded approval event resolves
  to `:deferred`, never approved; candidate files under reserved
  policy paths deny with `:policy-path-touched-by-candidate`.
  Evidence: `src/axiom/policy.clj` (`resolve`, `policy-approve-event`);
  `axiom.policy-test` (17 tests, incl. candidate-branch policy
  sources, unapproved digests, supersedes chains).
- **Check publication bound to the candidate and evaluator identity**
  — satisfied. Spec 0005 R5: publication is bound to exact candidate
  identity (repo slug, PR number, base/head/tree SHAs) and the
  configured evaluator identity; mismatch is an operational failure
  with zero writes; idempotent per (candidate, gate set, policy
  digest) via external ID `axiom-gate/<sha256>`.
  Evidence: `axiom.adapters.checks/publish!`, `no-test-opens-a-socket`
  discipline (fake Checks API only in tests); `axiom.checks-test`
  (publication binding, idempotency, external-ID determinism,
  candidate/evaluator mismatch, invalid-decision refusal).
- **Protected branch / merge-queue integration, including merge-group
  evaluation where available** — satisfied, with a scope note. Spec
  0005 R6: protection observation (required checks by
  provider-assigned check-run ID, required approvals,
  dismiss-stale-reviews, enforce-admins), merge-group candidate
  shapes, `:governance/admin-bypass` events with actor and reason;
  a bypassed gate reports `:bypassed`, never `:allow`. Scope note:
  there is no live provider, so the *interaction* with real
  protections/merge queues is not proven — the spec records the
  protection assumptions minimally (documented in the slice-3
  verification record) and proves the *handling* logic: when
  protections cannot be read or configured, advisory mode; bypasses
  are reported as bypasses. Evidence: `axiom.capability-test`
  (protection observation shape, merge-group candidate shape,
  admin-bypass event, `:bypassed` outcome).
- **Governance change handling and explicit authorization
  separation** — satisfied. Spec 0005 R2: three explicit roles
  (authorizer = owner, evaluator, candidate); governance events in
  the ledger as distinct kinds with authorizer identity and digests;
  a run that cannot name its policy approval is `:invalid`;
  `:governance/policy-revoked` is authorizer-governed and resolves
  revoked digests to `:deferred`, never allow. Evidence:
  `axiom.gate-ledger-test` (governance round-trips, missing
  authorizer → `:invalid`, duplicate dedup); CLI `policy-approve`
  with supersession.

## Gate audit (design's M4 gate conditions)

- **A candidate cannot pass by editing policy** — satisfied. A
  policy digest that does not match a recorded approval event is
  rejected; candidate files under reserved policy paths deny.
  Tested (edited-policy candidates, candidate policy relaxation
  in the 41-case adversarial corpus).
- **A candidate cannot pass by replacing the verifier** — satisfied.
  Verifier configuration replacement is denied unless the new
  configuration carries its own governance approval event
  (`:governance/verifier-config-approved`).
  Tested (`verifier-config-mismatch`, T7 corpus).
- **A candidate cannot pass by renaming a check** — satisfied.
  Check identity is the provider-assigned check-run ID plus
  workflow identity; display titles are never consulted.
  Tested (`renamed-check` unit test, renamed-checks corpus
  cases, `bypass-classes-never-allow`).
- **A candidate cannot pass by omitting required verification** —
  satisfied. An obligation with no matching completed evidence is
  `:unknown`, never allowed (deny-by-default: allow requires
  every required gate `:satisfied`). Tested (omitted-verification
  corpus cases, `bypass-classes-never-allow`).
- **A candidate cannot pass by presenting a stale success** —
  satisfied. A check run whose candidate base/head SHA has moved
  is `:stale` → deny (the 0004 stale semantics, now enforced
  rather than advisory). Tested (`stale-success` corpus cases).
- **Protection assumptions and administrator bypasses are
  documented** — satisfied. The deployment assumptions (provider
  exposes protection state, owner can configure protections; absence
  of admin-bypass reporting is not evidence of absence) are
  recorded in the slice-3 verification record; admin bypasses are
  ledger events reported as `:bypassed`, never `:allow`. An
  `:invalid` decision stays `:invalid` under a bypass.
- **Advisory mode instead of claimed enforcement** — satisfied. The
  R8 deployment capability check runs before the Checks API writer
  is selected; any failed answer yields advisory mode
  (`:report/provider-writes 0`, `:report/enforcement-claimed
  false`), and the adapter refuses to construct in advisory mode.
  Tested (`axiom.capability-test` — every single-no and multi-no
  case; advisory `publish-check` exit 0 with zero writes in the
  CLI probes). External protection/permission changes are the
  repository owner's act, never the software's — recorded as an
  explicit boundary.

## Honest limits (carried from the spec record)

- The real `github-checks-api` write path is implemented but
  exercised only through the in-memory fake: no test opens a socket
  (enforced by the `no-test-opens-a-socket` static test), and this
  review claims nothing about live-provider behavior — request
  shapes, pagination edge cases, provider-side idempotency
  semantics are unproven in production.
- `:trust/remote-ci` issuance logic is proven at the predicate
  level (evaluator identity + current trusted observation +
  evaluator-bound publication); the trust flags the rule reads are
  attested by the adapters, so the tests prove the logic, not
  production trust.
- Replay of gate decisions is byte-identical reproduction of the
  stored record (hash chain + payload digest), not re-evaluation;
  it proves the ledger preserved the record intact, not the truth
  of the recorded decision.
- Test evidence is bounded synthetic observation, not a formal
  proof and not a production trust attestation. The gate is not
  claimed un-bypassable in production — only that the named
  bypass classes are denied by the design and tested against
  synthetic adversaries.
- A gate allow is not authorization to execute, merge, deploy or
  mutate anything; automatic merge is explicitly out of scope
  (M5/M6). Axiom did not drive this spec run; self-hosting policy
  promotion gates remain M5.

## Verdict

**M4 is COMPLETE.** Every design-listed M4 deliverable and every M4
gate condition is satisfied by Verified spec 0005, with evidence
recorded in its verification record (local check + branch CI +
post-merge main CI). The one caveat is production exposure: the
write path has never touched a live provider, and production trust
establishment is a deployment act, not a property of this code. That
is an honestly documented boundary, not a missing deliverable.

Out of scope, deliberately not claimed: M5 (agent execution, leases,
outbox, self-hosting), M6 (consumer pilot, v1 release validation),
and Axiom v1 acceptance.

## Recommended next bounded units

1. **0004 `--pr` spec amendment** (docs-only): decide whether
   `--pr` is required or define the repo-at-SHA observation shape —
   still open from the M3 review; it touches the M4 CLI contract,
   so it should land before any M4 consumer pilot.
2. **M4 deferred ergonomics** (from the M1 review): JSON CLI
   interchange, source digest / approval provenance reconciliation,
   richer evidence schemas, public schema export — bounded specs
   that make the enforced gate consumable, none of them safety
   properties.
3. Only then: author the **M5 spec** (supervised agent execution:
   fake-agent adapter, isolated workers, leases/outbox, context
   projection) to Accepted, per the spec-driven sequence.
