# 0001 — Offline Nomos foundation

Status: Accepted for implementation; verification pending.

Authority: the repository owner's 2026-09-20 instruction, “Start to implement
axiom”, with the attached design plan, authorizes this bounded initial slice.
This records implementation scope, not an independent security review or v1
acceptance. The larger design remains proposed.

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
