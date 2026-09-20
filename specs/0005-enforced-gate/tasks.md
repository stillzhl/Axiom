# Tasks and acceptance gates

- [x] T1: Implement `axiom.gate` (pure port): gate decision model
  (`:allow` / `:deny` / `:defer` with named reasons), the five
  anti-bypass rules (R4) as pure predicates over validated
  observations, quarantine-safe input shape (no raw candidate strings
  used as gate names, policy references or keys), check identity by
  provider-assigned check-run ID plus workflow identity, stale
  semantics on base/head movement, and `:trust/remote-ci` issuance
  rules (evaluator identity + current trusted observation + enforcement
  capability, all required). No I/O; no new production dependencies;
  synthetic fixtures only. Acceptance: adversarial unit tests feed
  renamed checks, edited-policy candidates, omitted verifications and
  stale successes and assert deny/defer with named reasons, never
  allow; unknown or malformed required inputs cannot admit actions.
- [x] T2: Implement `axiom.policy`: approved-policy loading from a
  deployment-configured source, SHA-256 content-digest pinning over the
  canonical EDN encoding, approval-event lookup
  (`:governance/policy-approved` with supersession chains), rejection
  of policy descriptors pointing at the candidate ref, and rejection
  of digests with no approval event. Acceptance: a policy whose digest
  lacks an approval event makes every gate `:defer` with reason
  `:no-approved-policy`; a candidate-branch policy source is refused
  structurally.
- [x] T3: Ledger integration: `:decision/gate-evaluation` and
  `:governance/*` payload kinds through the 0002 append path
  (transactional sequence, hash chain, event-id/dedup-key dedup),
  strict envelope validation extended for the new kinds (including
  the `:trust/remote-ci` issuance rule — rejected without a valid
  evaluator-bound publication reference), forward-only schema v4
  migration, rebuildable snapshots with replay-equivalence as the hard
  invariant. Acceptance: replay of a prefix reproduces recorded gate
  decisions verbatim with their policy digests; forged trust marks are
  `:invalid` at validation.
- [x] T4: Implement `axiom.adapters.checks`: the only
  provider-mutating namespace, behind the `axiom.gate` port, using
  `java.net.http.HttpClient`. Constructor requires a passing R8
  capability record; a single write operation (check-run create/update
  bound to exact candidate and evaluator identity); idempotent per
  (candidate identity, gate set, policy digest); no code paths for any
  other provider mutation. A fake in-memory Checks API (records every
  attempted write, inspectable) drives tests with zero network.
  Acceptance: publication binds candidate/evaluator/policy identities
  in the run output; republishing is idempotent; without a passing
  capability record the adapter refuses to construct.
- [x] T5: Capability check, advisory mode, and protection
  observation: deployment capability record from the three R8
  answers; advisory-mode reporting when enforcement cannot be
  configured (evaluation produced and recorded, enforcement never
  claimed); trusted observation extension with branch-protection state
  (required checks by check-run ID, approval counts,
  dismiss-stale-reviews, enforce-admins); merge-group candidate
  identity (merge-group head SHA plus grouped PRs) where the provider
  offers merge queues; admin bypasses recorded as
  `:governance/admin-bypass` and reported as `:bypassed`, never as
  passed. Acceptance: advisory deployments exit 0 with an explicit
  advisory-mode report and zero provider writes; bypassed gates never
  report allow.
- [ ] T6: CLI: `gate --repo OWNER/NAME --pr N [--policy DIGEST]`
  (pure evaluation, EDN decision), `publish-check` (capability check
  then publication, or advisory-mode report — the only provider-
  mutating command), `policy-approve` (records governance approval
  events, distinct from candidate evaluation). Exit contract
  0/4/5 matching 0002–0004. Acceptance: invalid input exits 4,
  operational failure exits 5; `gate` never mutates provider state;
  `publish-check` in advisory mode performs zero writes and says so.
- [ ] T7: Adversarial and boundary tests: the design §13 cases
  relevant to M4 — candidate policy relaxation, renamed checks,
  omitted required verification, stale success presentation,
  verifier-config replacement, forged `:trust/remote-ci`, forged
  producer claims on published runs, duplicate publication, prompt/
  annotation text attempting to override policy, quarantine
  violations (candidate bytes reaching policy loading), capability
  check failures, protection-state changes mid-evaluation.
  Acceptance: an independent expected-results corpus drives the
  admission tests (expected outcomes specified before the
  implementation shapes them); every named bypass class is denied in
  tests; no test opens a socket or requires live network.
- [ ] T8: Verification: full `./scripts/check` green, update this
  spec's `verification.md` with exact commands, test counts and
  evidence, acceptance-gate review against `acceptance.md`, and mark
  the spec Verified only with real evidence. No M4 milestone or v1
  acceptance is claimed from this spec alone; milestone gates belong
  to the design's M4 gate review.
