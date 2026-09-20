# Verification record

State: spec authored and Accepted 2026-09-20; no implementation exists;
verification of the implementation is pending.

This does not mark the broader M2 milestone or Axiom v1 Verified.

## Spec acceptance evidence — 2026-09-20

The slice is docs-only: five spec files added under
`specs/0002-durable-ledger/` (requirements.md, design.md, tasks.md,
verification.md, acceptance.md). No source, test or script file was changed.
`./scripts/check` was run to confirm the change is inert against the existing
test corpus and CLI gates (result recorded below). No implementation evidence
is claimed; the acceptance.md gates define what "Verified" will require.

| Requirement | Implementation evidence |
| --- | --- |
| R1–R9 | Pending: tasks T1–T8. |

## Limits and deferred work

- No durable ledger, replay, snapshot, migration or export-bundle code
  exists yet; every R1–R9 behavior is specified but unverified.
- The design's remaining M2 items (local Git adapter, artifact digesting
  and retention, local diagnostic verification runner) are explicitly out
  of this spec and deferred to `0003+`.
- The design's `leases` and `action_outbox` tables belong to action
  dispatch (M4+) and are out of scope.
- SQLite crash behavior of our own append protocol is specified as future
  test work (T7); it has not been run.
- Hash chaining is tamper-evident, not tamper-proof: an attacker who
  controls the whole database can rewrite the chain. Stronger attestation
  (trusted external checkpoints or signatures) is deferred beyond the
  initial MVP, per the design.
- The local ledger is single-process; multi-writer coordination,
  replication and network protocols are out of scope.
- Self-hosting is planned with independent evaluator/policy promotion
  gates; Axiom is not driving this implementation run.
