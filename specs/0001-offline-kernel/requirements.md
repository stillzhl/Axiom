# 0001 — Offline Nomos foundation

Status: Accepted for implementation; verification pending.

Authority: the repository owner's 2026-09-20 instruction, “Start to implement
axiom”, with the attached design plan, authorizes this bounded initial slice.
The owner's 2026-09-20 autopilot instruction authorizes continued bounded
slices under this spec until the M1 CLI surface is complete. This records
implementation scope, not an independent security review or v1
acceptance. The larger design remains proposed.

## Spec naming and scope decision (recorded 2026-09-20)

The bootstrap patch created this spec as `0001-offline-kernel`; the proposed
design names it `0001-foundation`. The number and directory are kept:
`0001-offline-kernel` is the accepted implementation spec covering the
bootstrap's M0 work plus the M1 pure symbolic kernel and its offline CLI.
Future specs (`0002`, …) cover M2+ work (durable ledger, adapters). No
M0/M1 milestone acceptance or verification is claimed from partial evidence;
the spec's verification record stays honest about what was and was not run.

## Repository boundary (owner instruction, 2026-09-20)

The Axiom repository stays consumer-agnostic. Consumer-specific contracts,
policies, fixtures, scenarios and verification evidence belong in the consumer
repository (e.g. HomeKV), never here. All fixtures and examples in this spec
are synthetic and invented for tests.

## Requirements

- R1: Read one bounded EDN value without reader evaluation, tags, trailing forms,
  unsupported scalar types, excessive nesting or oversized input. Strict schemas
  reject unknown fields, duplicate IDs, unresolved references, dependency cycles,
  empty mandatory gates and invalid paths.
- R2: Canonical encoding is versioned, type preserving and independent of map/set
  insertion order. Candidate identity includes repository, base, head, tested
  commit/tree, contract, policy and verification recipe digests.
- R3: A pure reducer consumes ordered in-memory evidence and claim events.
  Claims never count as observations. Reusing an event ID is invalid. All time
  used by evaluation is an explicit input.
- R4: Nomos evaluates accepted specs, recursive dependencies, observed path scope
  and required test evidence. Violations deny; otherwise unknowns defer; only all
  satisfied gates allow. Every result has named rules and explanations.
- R5: Qualifying evidence matches the full candidate, producer, recipe, suite,
  profile and obligation. Missing/stale/future/skipped evidence defers. A newer
  attempt supersedes an older attempt; conflicting results within the selected
  attempt defer. Missing scope observations defer. Invalid input produces no
  admission decision.
- R6: CLI validation and evaluation emit EDN with exit codes 0 success/allow,
  2 deny, 3 defer, 4 invalid input, 5 operational failure. The checked-in synthetic
  scenarios cover allow, missing, stale and failing evidence.
- R8: Read-only reporting commands emit EDN and return exit 0 when a valid
  report was produced, 4 on invalid input, 5 on operational failure; readiness
  is reported inside the payload and carries no gate semantics (automation
  must use `evaluate`). `status` reports every contract task with its decision,
  rule counts and blockers. `next` reports eligible tasks (all declared
  dependencies evaluate to allow) with missing prerequisites explained, and
  the remaining tasks with their unmet dependencies. `explain` replays the
  scenario, verifies the decision identity and returns a structured
  per-rule explanation with missing inputs and remediation suggestions; when
  a supplied decision id does not match the recomputed decision, the report
  says so instead of explaining a stale record. The CLI input contract for
  this slice is scenario EDN files; the design's `--repo PATH` form is
  deferred until consumer contracts exist (M2+).
- R7: Tests include adversarial admission cases and deterministic generated
  invariant checks. Preserve the existing MIT license. CI uses pinned tooling.

## Explicit boundaries

This is an advisory evaluator for supplied offline snapshots. Producer names,
approval references and scope observations in those snapshots are NOT
authenticated. An allow result is not authorization to execute or merge.
Only synthetic test-result evidence is supported. No repository/network adapter,
durable ledger, worker, capability, source-digest reconciliation, JSON protocol,
general glob matching, independent governance acceptance or enforcement exists
in this slice. Duplicate evidence deliveries are rejected, not deduplicated.
Changed files support regular-file add/modify/delete/rename only; symlinks,
submodules and other modes defer. Full M0/M1 completion is not claimed.
