# Design

## Ports and adapters

- `axiom.gate` — the pure port. Inputs: a validated candidate
  observation (0004 shape: repo identity, PR number, base/head/tree
  SHAs, runs, jobs, attempts, approvals, protection state), an approved
  policy (policy id, content digest, approval event reference, gate
  set), the evaluator identity, and the deployment capability record.
  Outputs: a gate decision map (`:gate/decision` one of
  `:allow` / `:deny` / `:defer`, `:gate/reasons` — named reasons for
  every required gate, `:gate/candidate` — exact candidate identity,
  `:gate/evaluator` — evaluator identity, `:gate/policy` — policy
  digest and approval event id, `:gate/trust` — evidence trust marks).
  Pure and side-effect-free; no I/O, no network, no database.
- `axiom.policy` — approved-policy loading and approval-event
  validation. Loads policy content from a deployment-configured policy
  source (a ledger, a pinned path, a policy service — chosen by the
  consumer), pins it by content digest, and checks the digest against a
  recorded `:governance/policy-approved` event. The policy source is
  configured at deployment, never derived from the candidate. Candidate
  branches are not policy sources — structurally, `axiom.policy`
  receives a policy-source descriptor and rejects any descriptor that
  points at the candidate ref.
- `axiom.adapters.checks` — the only namespace permitted to mutate
  provider state, and only through check-run publication. Behind the
  `axiom.gate` port. Constructor takes the deployment credential and
  the R8 capability record; without a passing capability record the
  adapter refuses to construct (the CLI then reports advisory mode).
  Single write operation: create/update a check run bound to exact
  candidate and evaluator identity. No other provider mutations — no
  code paths for merges, branch updates, label changes, or protection
  edits.
- The 0004 `axiom.adapters.github` remains read-only; the checks
  adapter is a separate namespace so a read-only deployment simply
  never loads the write adapter.

## Authorization model

Roles are data, not ambient authority:

- `:authorizer/owner` — the repository owner. Authorizes policy
  approvals, verifier-configuration approvals, and
  protection/permission changes. Approval events carry the owner
  identity and are recorded in the ledger.
- `:evaluator/trusted` — the trusted evaluation workflow or GitHub
  App instance. Bound to a credential-derived identity at deployment.
  The only identity that can publish check runs (R5) and attach
  `:trust/remote-ci` (R7).
- `:candidate/untrusted` — the PR under evaluation. Its content is
  observation input only; it authorizes nothing.

Separation is enforced by event kinds: `:governance/*` events require
an authorizer identity and a digest; `:decision` events from the gate
require an evaluator identity and a policy approval reference. An
event with a missing or mismatched role identity is `:invalid` and is
rejected by ledger validation (0002/0003 strict envelopes), never
normalized.

Governance change handling: a policy approval supersedes a prior
digest; the supersession is explicit (`:governance/policy-approved`
carries `:supersedes <digest>`). Gate runs pin the exact policy digest
they executed under; two runs under different digests are distinct
evaluations, never merged. A candidate that edits files under the
deployment's reserved policy paths is denied with reason
`:policy-path-touched-by-candidate` (R1, R4a).

## Policy approval and digest pinning

Policy identity is `(policy-id, content-digest)`. Approval is an
event, not a file's presence: the gate accepts a policy only when a
`:governance/policy-approved` event for `(policy-id, digest)` exists
in the ledger and no `:governance/policy-revoked` event for that
digest exists. Revocation is authorizer-governed (R2 amendment
2026-09-20): it names the revoked digest and the authorizer, and a
revoked digest resolves to `:deferred` with reason
`:policy-approval-revoked`. Digest
algorithm is SHA-256 over the canonical EDN encoding (the 0001
canonical-decision encoding, reused). Unknown or unapproved digests
make every dependent gate `:defer` with reason
`:no-approved-policy`, never allow.

## Quarantine model

Candidate-controlled bytes: PR diff content, commit messages, workflow
display names, check-run titles set by candidate-branch workflows,
PR comments, review bodies, fork metadata. Trusted basis: the approved
policy, the trusted provider observations (0004 adapter output plus
branch-protection state read by the evaluator's own credential), and
the evaluator identity.

The quarantine boundary is a function boundary: `axiom.gate/evaluate`
takes `(policy, observation, evaluator-id, capability)` — the
observation has already been validated and trust-marked by the
adapters; nothing in `evaluate`'s input is a raw candidate string
used as a key, a gate name, or a policy reference. Gate names in the
policy are matched against provider-assigned check-run IDs and
workflow identities (R4c), never against display titles the candidate
can set. This is testable: feed `evaluate` an observation whose
display names claim to be a required gate and assert the obligation
stays `:unknown`.

## Anti-bypass gates (R4, made concrete)

For each bypass class, the design names the denying mechanism:

- Policy edit in candidate: denied at observation time — files under
  reserved policy paths in the candidate diff are a finding
  `:policy-path-touched-by-candidate`, and the gate denies.
- Verifier replacement: the evaluator configuration is deployment
  data, loaded by `axiom.policy` from the approved source; the
  candidate cannot name a different verifier. A *governance-approved*
  verifier change (R2) is a new approval event, and the old policy
  digest remains pinned for runs that cite it.
- Check rename: required gates are bound to (workflow identity,
  check-run ID); display-name matching is not a satisfaction path.
- Omitted verification: obligations with no completed, current,
  candidate-bound evidence are `:unknown`; allow requires all
  required gates `:satisfied`.
- Stale success: a check run is current only if its recorded
  base/head SHAs equal the fresh observation's SHAs; otherwise
  `:stale`, and stale does not satisfy. The 0004 stale semantics are
  reused, but here they are *enforced* — the gate denies on stale
  where 0004 only reported advisory staleness.

## Check publication (R5)

The checks adapter publishes one check run per (candidate identity,
gate set, policy digest). The run's external ID encodes the gate
decision digest so republishing is idempotent and auditable: the
provider-visible run always names the policy digest and the evaluator
identity in its output summary. Publication failures are operational
(5); a published run whose candidate identity mismatches the
evaluation input is a defect surfaced as an operational failure, not
silently corrected.

## Trust marks

`:trust/remote-ci` is issued only by `axiom.gate/evaluate` when all
of: the evaluator identity is the configured trusted evaluator, the
observation is a current trusted observation (not a fixture, not
stale), and the capability record shows enforcement mode. The ledger
validation for `:trust/remote-ci` records (extended 0003 strict
envelope) rejects the mark unless the record carries a valid
evaluator-bound publication reference. The 0001–0004 marks are never
promoted: there is no code path that rewrites a trust mark.

## Branch protection and merge groups

The trusted observation extends the 0004 shape with
`:protection` — required status checks (by check-run ID, not name),
required approval count, dismiss-stale-reviews flag, enforce-admins
flag — read with the evaluator's credential. Merge-group evaluation
(where the provider offers merge queues): the candidate identity for
a merge-group run is the merge-group head SHA plus the grouped PR
identities; staleness is computed against the merge-group head. Admin
bypasses are observed where the provider API exposes them and recorded
as `:governance/admin-bypass` with actor and reason; a bypassed gate
reports `:bypassed`, a distinct outcome from `:allow`.

## Ledger integration

New payload kinds through the 0002 append path, additive to the
existing schema: `:decision/gate-evaluation` (gate decision with
candidate/evaluator/policy identities and reasons) and
`:governance/*` events (policy approvals, verifier-config approvals,
protection changes, admin bypasses). The 0002 invariants apply
unchanged: transactional sequence, hash chain, event-id/dedup-key
dedup, forward-only migrations (schema v4), rebuildable snapshots
with replay-equivalence as the hard invariant. Replay of a prefix
reproduces recorded gate decisions verbatim; it never re-evaluates
against a different policy — the policy digest travels with the
record.

## Capability check and advisory mode (R8)

The capability record is computed at deployment from three answers:
(1) does the deployment credential have Checks write permission on
the target repository; (2) can the deployment read branch-protection
state; (3) are the required protections configurable by the owner.
If any answer is no, the record says `:mode :advisory` and the CLI
`publish-check` refuses to construct the write adapter, reports
advisory mode, and exits 0 with the evaluation recorded locally.
Enforcement is never claimed in advisory mode — the design treats a
false enforcement claim as the worst failure of this spec. Changing
protections or permissions is the owner's act; the software asks for
authorization, it does not take it.

## Fixtures and offline reproduction

The port defines a synthetic fixture format: candidate observations
(0004-compatible), approved-policy fixtures with recorded approval
events, evaluator identities, and capability records. A fake Checks
API (in-memory, inspectable, recording every attempted write) lets
the test suite exercise publication, idempotency, and the advisory
fallback with no network. Gate decisions recorded from fixtures
replay offline byte-identically. All fixture repositories, SHAs,
logins and tokens are invented (`synth-*`); no live credentials.

## CLI

- `gate --repo OWNER/NAME --pr N [--policy DIGEST]` — pure
  evaluation against the approved policy; EDN decision on stdout.
- `publish-check --repo OWNER/NAME --pr N` — capability check, then
  publication through `axiom.adapters.checks`, or advisory-mode
  report. The only command that may mutate provider state.
- `policy-approve --policy PATH --approver ID` — records a
  `:governance/policy-approved` event in the ledger (a governance
  action, distinct from candidate evaluation; the approver identity
  is recorded, and this command itself requires the deployment to be
  in a mode where governance writes are authorized).
- Exits: 0 valid report, 4 invalid input, 5 operational failure —
  the 0002–0004 contract.

## Out of scope (explicit)

Agent execution, action dispatch, leases, the action outbox and
isolated workers (M5). Axiom-driven self-hosting and policy
promotion gates for Axiom's own development (M5). Automatic merge or
any merge capability — an allow decision is not a merge instruction.
Consumer-specific policy content and real consumer evidence (lives in
consumer repositories). Signatures and tamper-proofing beyond the
0002 tamper-evident ledger. Worker isolation for the evaluator
process itself (M5). Live-network tests.
