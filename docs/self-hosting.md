# Axiom developing Axiom

The owner requested self-hosting on 2026-09-20: once mature enough, Axiom should
drive its own development. This is a product goal, not a current capability.

## Adoption stages

1. **Offline regression corpus (now):** test Nomos against synthetic snapshots
   with independent expected outcomes. No action privileges.
2. **Advisory self-evaluation (after M2/M3):** Axiom owns its own `.axiom/`
   contract, requirement mappings and CI evidence. Observe real PRs with a pinned
   released evaluator. Compare decisions with maintainer review. Record false
   admissions, false blocks and unknowns; never treat self-authored claims as CI.
3. **Protected self-gating (after M4):** a trusted workflow outside the candidate
   loads the approved policy and a pinned evaluator. Establish merge-queue or
   equivalent atomic identity guarantees. Candidate code cannot replace the
   evaluator, required verification recipe or governing policy for its own PR.
4. **Bounded self-development (after M5):** Axiom selects an accepted task,
   prepares context, supervises an isolated coding agent, collects evidence and
   opens a reviewable PR. Enforce leases, scoped capabilities, attempt budgets,
   cancellation and reconciliation. Merge remains a separate capability.
5. **Broader autonomy (after measured pilot):** expand scope only after the
   predefined holdout corpus and pilot meet reviewed reliability thresholds.

## Bootstrap trust and upgrades

Maintain two explicit identities: the running supervisor/evaluator and the
candidate it evaluates. A candidate can include a new Axiom version, but it
cannot substitute itself as the authority for its own acceptance. Run candidate
tests as untrusted worker code, and retain regression evidence under the pinned
previous evaluator. Upgrades to rules, schemas, verification recipes, required
checks, capabilities or governing policy are a separate review class. Require
independent maintainer acceptance and promotion of the new trusted release.

Keep an independently specified adversarial corpus and holdout cases. Tests
generated or altered by the same candidate cannot be the sole acceptance basis.
Record accepted-spec revision, candidate and base identity, evaluator/policy
digests, evidence provenance and replay bundle for every admitted change.
Preserve a known-good evaluator and rollback procedure. An indeterminate effect,
missing observation, exhausted budget or conflicting evidence stops dependent
mutations and reports an actionable blocker.

## Readiness gates before the first self-driven PR

- Durable replay and crash recovery pass with pinned decision inputs.
- Read-only Git/CI collectors demonstrate completeness and candidate invalidation.
- Independent policy loading and trusted verification resist candidate tampering.
- Fake-agent end-to-end execution passes isolation, lease, outbox and budget tests.
- A maintainer accepts Axiom's own task contract and limited execution capability.
- An observed pilot meets predeclared reliability thresholds against holdout tasks.

The first self-driven task should be a bounded documentation or adapter change
outside the trust-critical kernel. Kernel changes can follow with the separate
upgrade review path. This prevents an “allow” result from silently becoming
permission to rewrite the rules that produced it.
