# 0005 — Enforced verification gate (trusted evaluation and check publication)

Status: Verified (2026-09-20) — every implementation gate in
acceptance.md is satisfied with real evidence recorded in
verification.md. No M4 milestone or v1 acceptance is claimed.

Authority: the repository owner's 2026-09-20 instruction, "Start to implement
axiom", with the attached design plan, authorizes bounded slices. The owner's
2026-09-20 autopilot instruction authorizes continued bounded slices under the
spec-driven sequence (requirements → design → tasks → implementation →
verification), one focused branch/PR per slice. This records implementation
scope, not an independent security review or v1 acceptance. The larger design
remains proposed.

## Spec naming and scope decision (recorded 2026-09-20)

Specs `0002-durable-ledger` (SQLite event store, replay, bundles),
`0003-local-observations` (local Git adapter, artifact digests, diagnostic
runner) and `0004-github-observations` (read-only authenticated GitHub
observation, `check-pr`, advisory `can-merge`) are Verified, and the design's
M1, M2 and M3 milestone gate reviews are Complete. This spec covers the
design's M4 item list — Enforced verification gate: a trusted evaluation
workflow (or dedicated GitHub App path appropriate to deployment
permissions), approved-policy loading independent of candidate content,
check publication bound to the candidate and the evaluator identity,
protected branch / merge-queue integration including merge-group evaluation,
and governance-change handling with explicit authorization separation. This
spec does NOT cover agent execution, action dispatch, leases, the action
outbox or isolated workers (M5), nor the consumer pilot or v1 release
validation (M6). It does not introduce Axiom-driven self-hosting: self-hosting
policy promotion gates remain M5.

## Repository boundary (owner instruction, 2026-09-20)

The Axiom repository stays consumer-agnostic. Consumer-specific contracts,
policies, fixtures, scenarios and verification evidence belong in the consumer
repository (e.g. HomeKV), never here. All fixtures and examples in this spec
are synthetic and invented for tests. No live GitHub credentials, tokens or
real repository identities may appear in this repo's fixtures or evidence.

## Requirements

- R1: Approved-policy loading independent of candidate content. The
  enforced gate evaluates a candidate against an *approved* policy: the
  policy (gates, required verifications, evidence schemas, approver
  identities) is loaded from a policy source pinned by content digest and
  recorded as an approval event in the ledger (0002 append path), never
  read from the candidate branch, fork, or any path the candidate can
  write. A policy whose digest does not match a recorded approval event
  for the same policy id is rejected; a candidate that contains policy
  files under the paths reserved for approved policy is denied, not
  consulted. The implementation must distinguish "the policy the gate
  enforces" from "files the candidate happens to contain" structurally,
  not by convention.
- R2: Explicit authorization model with separation of roles. Three roles
  are explicit in the gate model: (a) the *authorizer* — the repository
  owner, who authorizes protection/permission changes and policy
  approvals; (b) the *evaluator* — the trusted evaluation workflow or
  GitHub App instance, bound to an evaluator identity; (c) the *candidate*
  — the untrusted PR content. Authorization events are recorded in the
  ledger as distinct event kinds (`:governance/policy-approved`,
  `:governance/policy-revoked`, `:governance/verifier-config-approved`,
  `:governance/protection-changed`) carrying the authorizer identity,
  the digest of what was approved, and the prior digest being
  superseded. A gate run records which policy approval it executed
  under; a run that cannot name its policy approval is `:invalid`,
  never allowed. Amendment 2026-09-20 (final slice):
  `:governance/policy-revoked` is authorizer-governed like the
  approval kinds: it names the revoked policy digest and the
  authorizer, and once recorded the revoked digest resolves to
  `:deferred` with reason `:policy-approval-revoked`, never to
  allow — revocation withdraws a policy without deleting history.
- R3: Untrusted inputs stay quarantined. Candidate content (PR diff,
  commit SHAs, workflow names, check names presented by the candidate
  branch, approval comments on the candidate) is data under evaluation
  only. It never flows into policy loading, evaluator configuration, the
  Checks API credential, or the evaluator's own trust assertions. The
  design must show the quarantine boundary: the functions that read
  candidate-controlled bytes and the functions that make allow/deny
  decisions take disjoint inputs, with the approved policy and the
  trusted observations as the only shared basis. Prompt text, workflow
  annotations and check titles from the candidate cannot override,
  rename or relax a required gate.
- R4: Anti-bypass gates (the design's M4 gate, made testable). A
  candidate cannot pass by any of: (a) editing approved policy files —
  denied (R1); (b) replacing or re-pointing the verifier configuration —
  denied unless the new configuration carries its own governance approval
  event (R2); (c) renaming a check to impersonate a required one — check
  identity is the provider-assigned check-run ID plus workflow identity,
  never the display name alone; a renamed check does not satisfy the
  required gate; (d) omitting required verification — an obligation with
  no matching completed evidence is `:unknown`, never allowed; (e)
  presenting a stale success — a check run whose candidate base/head SHA
  has moved since the run is `:stale` and does not satisfy the gate (the
  0004 stale semantics, now enforced rather than advisory). Each bypass
  attempt produces a named deny/defer reason; none produces allow.
- R5: Check publication bound to candidate and evaluator identity. The
  trusted evaluator publishes check runs through the Checks API writer
  adapter, and every published run is bound to the exact candidate
  identity (repository, PR number, base SHA, head SHA, tree identity) and
  the evaluator identity (the trusted producer from the deployment's
  credential). A published run whose candidate identity does not match
  the evaluation input is a defect, reported as an operational failure;
  a run published by any identity other than the configured evaluator is
  not a valid enforcement record. Publication is idempotent per
  (candidate identity, gate set, policy digest): republishing the same
  evaluation does not duplicate logical effects.
- R6: Protected branch, merge-queue and merge-group integration. The
  gate observes branch-protection state (required status checks, required
  approvals, dismiss-stale-reviews behavior, enforce-admins) as part of
  the trusted observation, and merge-group evaluation where the provider
  offers it. Administrator bypasses are recorded explicitly as
  `:governance/admin-bypass` events with the actor and the reason; a
  bypassed gate is reported as bypassed, never as passed. The spec
  requires documenting the deployment's protection assumptions (what
  protections are assumed present, what happens when they are absent or
  changed).
- R7: `:trust/remote-ci` marking — the reserved mark, now producible.
  Only the trusted evaluation workflow (GitHub App or
  deployment-authenticated evaluator, R5) can attach
  `:trust/remote-ci` to evidence records. The 0001–0004 marks
  (`:trust/local-diagnostic`, `:trust/provider-observed`,
  `:trust/provider-authenticated`) can never be promoted to
  `:trust/remote-ci` by any code path; a record carrying
  `:trust/remote-ci` without a valid evaluator-bound publication (R5)
  and a current trusted observation is rejected as `:invalid`. Downstream
  gates that require `:trust/remote-ci` treat any other mark as
  insufficient evidence (`:unknown`, never allowed).
- R8: Deployment capability check and advisory-mode fallback. Before
  selecting the Checks API writer, the implementation runs a
  deployment-specific capability check: does this deployment hold a
  credential permitted to publish checks on the target repository, and
  are the required branch protections configurable? If host enforcement
  cannot be configured, the gate reports *advisory mode* — the
  evaluation is produced and recorded, but the spec forbids claiming
  enforcement. External protection/permission changes require the
  repository owner's authorization; producing the software does not
  grant that authority, and the implementation must not silently acquire
  or assume it.
- R9: Ledger integration. Gate evaluations (allow/deny/defer with the
  named reasons), check publications (candidate identity, evaluator
  identity, policy digest, run IDs) and governance events (policy
  approvals, verifier-config approvals, protection changes,
  admin bypasses) are recorded through the 0002 append path as
  `:decision` and `:governance` payload records with the 0002
  invariants (transactional sequence, hash chain, event-id/dedup-key
  dedup, replay-equivalence of snapshots). Replay of a ledger prefix
  reproduces the same gate decisions for the recorded
  engine/version/time; a gate decision whose policy approval is no
  longer in the ledger prefix is reproduced as recorded, with its
  policy digest visible, never re-derived from a different policy.
- R10: CLI surface, enforcement-capable diagnostics. `axiom.cli
  gate --repo OWNER/NAME --pr N [--policy POLICY-DIGEST]` evaluates the
  candidate observation (0004 fixture-compatible) against the approved
  policy and emits the EDN gate decision (allow/deny/defer with reasons,
  candidate identity, evaluator identity, policy digest, trust marks);
  `axiom.cli publish-check` publishes the evaluation through the Checks
  API writer only when the deployment capability check (R8) passes —
  otherwise it reports advisory mode and exits without writing. Exit
  contract matches 0002–0004: 0 on a valid report, 4 on invalid input
  (malformed repo slug, unknown PR, bad policy digest, unreadable
  policy file), 5 on operational failure (API error, incomplete
  observation, missing policy approval, capability check failure). The
  CLI never mutates provider state except through the explicit
  `publish-check` capability path.
- R11: Core purity and adapter boundary. The evaluation core
  (`axiom.model`, `axiom.contract`, `axiom.world`, `axiom.prover`,
  `axiom.nomos`, `axiom.ledger`, `axiom.git`, `axiom.runner`,
  `axiom.github`) stays pure and side-effect-free. Provider mutation
  lives only in a new `axiom.adapters.checks` adapter (Checks API
  writes) behind a pure `axiom.gate` port, selected only after the R8
  capability check; the adapter has no code path for anything other
  than check-run publication. No other namespace opens sockets, spawns
  processes or touches the database file. Unknown or malformed required
  inputs cannot admit actions.
- R12: Honest trust levels and limits. A gate decision is allow only
  when every required gate is satisfied by current, candidate-bound,
  evaluator-published evidence under an approved policy; any gap is
  defer or deny with named reasons, never allow by default. Gate
  decisions authorize nothing beyond the gate itself: an allow is not
  authorization to execute, merge, deploy or mutate. The design records
  the protection assumptions and the administrator-bypass behavior of
  the deployment (R6). Test evidence is bounded synthetic observation,
  not formal proof and not a production trust attestation; this spec
  does not claim the gate is un-bypassable in production, only that
  the named bypass classes (R4) are denied by the design and tested
  against synthetic adversaries.

## Explicit boundaries

No agent execution, action dispatch, leases, action outbox or isolated
workers (M5). No Axiom-driven self-hosting: policy promotion gates for
Axiom governing its own development remain M5; this spec's evaluator is
a fixed, deployment-configured workflow/App, not an agent loop. No
automatic merge: `publish-check` reports evaluations; merging remains a
separate, explicitly authorized capability (M5/M6), never implied by
an allow decision. No consumer-specific policy content: approved-policy
fixtures are synthetic and generic; real consumer policies live in
consumer repositories. No signatures or tamper-proofing: the local
ledger remains tamper-evident, not tamper-proof (0002). No live-network
tests in `scripts/check` — provider writes are tested against a
recorded, inspectable fake of the Checks API; live network access is
never required to run the test suite. No change to 0001–0004 decision
bytes, exit contracts or ledger schema semantics beyond additive
`:decision`/`:governance` payload kinds. If host enforcement cannot be
configured, the implementation reports advisory mode (R8) instead of
claiming enforcement.
