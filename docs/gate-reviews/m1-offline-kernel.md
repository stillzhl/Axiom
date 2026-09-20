# M1 gate review — Pure symbolic kernel

Reviewed: 2026-09-20. Authority: the owner's standing Axiom autopilot
instruction; this is a docs-only milestone audit, not an independent
security review. Source: the proposed design plan, "M1 — Pure symbolic
kernel" (Implement list + Gate), plus §11's M1-era CLI expectations.
Candidate evidence: spec `0001-offline-kernel` on main `75cc6f6` — all
tasks T1–T7 complete (PR #2 `a9f10d3`, PR #3 `8528bff`), but note the
spec itself was never labeled Verified; T6/T7 completion is the
implementation-complete mark and this review supplies the
milestone-level judgment.

Rule: this review audits spec 0001 against the design's M1 criteria. It
does not edit any spec's claims and does not claim anything beyond M1
(no M0/M1 milestone acceptance beyond this verdict, no v1).

## Implement list audit

- **Versioned contract, observation, claim, evidence, and decision
  schemas** — satisfied. Spec 0001 R1: strict schemas reject unknown
  fields; R2: canonical encoding is versioned, type-preserving,
  insertion-order independent. Evidence: 0001 verification.md
  (`strict-schemas`, `canonical-identity`; local `./scripts/check`
  15 tests / 707 assertions, CLI exits allow 0 / missing 3 / stale 3 /
  failed 2).
- **Strict manifest validation and deterministic candidate
  normalization** — satisfied. R1: bounded EDN reader (no evaluation,
  no tags, bounded depth/size, single top-level value); strict
  validation of duplicate IDs, unresolved references, dependency
  cycles, empty mandatory gates, invalid paths. R2: candidate identity
  includes repository, base, head, tested commit/tree, contract,
  policy, and recipe digests. Evidence: `bounded-data-reader`,
  `malformed-field-types`, `path-safety`, `canonical-identity`.
- **In-memory event reducer with explicit time inputs** — satisfied.
  R3: pure reducer over ordered in-memory events; claims never count
  as observations; reusing an event ID is invalid; all evaluation time
  is an explicit input. Evidence: `replay-and-generated-invariants`,
  duplicate-event rejection tests.
- **Accepted-spec/dependency/scope/evidence-binding rules** —
  satisfied. R4/R5: accepted specs, recursive dependencies, observed
  path scope, and required test evidence binding; violations deny,
  unknowns defer, only all-satisfied allows; evidence must match the
  full candidate, producer, recipe, suite, profile and obligation.
  Evidence: `admission-corpus`, `dependency-gates`,
  `candidate-mutation-invalidates-evidence`, `reruns-and-conflicts`.
- **`validate`, `status`, `next`, `evaluate`, and structured
  explanations** — satisfied. R6: CLI `validate`/`evaluate` with exit
  contract 0 allow / 2 deny / 3 defer / 4 invalid / 5 operational.
  R8/T7: pure `ready-tasks`/`explain-decision` behind `status`,
  `next`, `explain` (exit 0 on a valid report; readiness reported
  inside the payload and carries no gate semantics — automation must
  use `evaluate`); `explain --decision` verifies decision identity
  and reports `:decision-id-mismatch` rather than explaining a stale
  record. `evaluate` decision digests byte-identical across the T7
  refactor on all four scenarios. Evidence: `status-report-shape`,
  `next-report-shape`, `explain-decision-shape`, `cli-read-commands`,
  read-gate CLI probes in `scripts/check`.
- **A synthetic example with incomplete, stale, failing, and
  sufficient evidence scenarios** — satisfied. `examples/synthetic-project/`
  ships allow/missing/stale/failed scenarios with the documented exit
  codes; scenario EDN files are the accepted CLI input contract for
  this slice (the design's `--repo PATH` form is deferred until
  consumer contracts exist — M2+, recorded in R8).

## Gate audit (design's M1 gate conditions)

- **A submitted claim cannot unlock an obligation** — satisfied. R3:
  claims never count as observations; R4/R5: un-evidenced
  prerequisites stay `:unknown` and defer, never allow. Evidence:
  `admission-corpus`, `dependency-gates`.
- **Malformed or incomplete input cannot allow a gated action** —
  satisfied. R1: malformed input rejected at the bounded reader and
  strict schemas (exit 4); R4/R5: invalid input produces no admission
  decision, missing evidence defers. Evidence: adversarial
  `malformed-field-types`, `bounded-data-reader`, the four scenario
  exit codes.
- **Identical inputs replay identically** — satisfied. R3/R2:
  generated map-order invariants; T7: `evaluate` digests byte-identical
  across the refactor on all four scenarios. Deterministic by
  construction of the pure reducer.
- **Candidate mutation invalidates earlier evidence** — satisfied. R5:
  evidence binds to the full candidate identity; a newer attempt
  supersedes, and mutation breaks binding. Tested explicitly in
  `candidate-mutation-invalidates-evidence`.
- **All denial/defer results name reasons** — satisfied. Every rule
  result is a record with `:status`, `:reason` and `:support`
  (`nomos.clj` rule constructor); `explain` returns structured
  per-rule explanations with missing inputs and remediation. Evidence:
  `cli-results`, `explain-decision-shape`.
- **A synthetic trusted producer is explicitly test-only** —
  satisfied. The spec's Explicit boundaries: producer names, approval
  references and scope observations in snapshots are NOT
  authenticated; an allow result is not authorization to execute or
  merge; only synthetic evidence supported.

## Deferred items (honest gaps — not gate failures)

These are named in the roadmap's M1 row and in 0001's own verification
limits. They were explicitly scoped out of the accepted spec 0001 by
owner instruction and are **not** part of the design's M1 Gate:

- **JSON interchange** — design §11 says machine output supports EDN
  and JSON; 0001 implements EDN only (the only JSON parser in `src/`
  is an internal provider-response reader in
  `axiom.adapters.github`, 0004 — not CLI interchange). Genuine gap.
- **Source digest / approval provenance reconciliation** — design §5
  names unresolved source digests in contract validation; 0001
  explicitly defers reconciliation (verification.md "Limits and
  deferred work"). Genuine gap.
- **Richer evidence schemas** — design §2 requires limits and trust
  levels to be visible in the evidence schema; 0001's schema is the
  minimal synthetic form, with structured explanations added in T7.
  Partially satisfied; richer provenance visibility is a follow-up.
- **Context / readiness commands** — readiness is already reported
  inside read-command payloads per R8 (no gate semantics); the
  design's *context projection* is M5's domain (§10.1), not M1. Kept
  as a named deferred item only insofar as it appeared in the
  roadmap row.
- **Public schema export / pinned lint+formatter** — named in 0001's
  limits as follow-ups; not M1 gate items.

None of these admit an action, and none weaken a gate condition:
they are interchange/export ergonomics, not safety properties.

## Honest limits (carried from the spec record)

- All evidence and producer identities are synthetic and
  unauthenticated; the synthetic trusted producer is test-only.
- In-memory reduction only; no durability, migration, external
  observations, isolated worker, execution capability or merge
  enforcement (M2+ specs now cover ledger/observations/GitHub).
- A green test run is bounded synthetic evidence, not a formal
  correctness proof or production trust attestation.
- Axiom did not drive this implementation run; self-hosting is planned
  with independent evaluator/policy promotion gates.

## Verdict

**M1 is COMPLETE at the gate level.** Every design-listed M1
deliverable and every M1 gate condition is satisfied by spec 0001's
T1–T7 scope, with evidence recorded in the spec's verification
record. The Deferred items section above is not papered over: those
follow-ups remain named, accepted-scope gaps and are the recommended
next bounded specs — they do not block the milestone verdict because
none is in the design's M1 Gate.

Out of scope, deliberately not claimed: M0 milestone acceptance
(the bootstrap gate was never separately reviewed; 0001 covers
bootstrap M0 work), M4/M5/M6, and Axiom v1 acceptance.

## Recommended next bounded units

1. **Spec 0005: JSON interchange + public schema export** (M1
   follow-up): machine-output JSON for the read/decision commands,
   versioned public schema export; must preserve EDN byte-identity.
2. **Spec 0006: source digest / approval provenance reconciliation**
   (M1 follow-up): unresolved source digests in contract validation,
   approval provenance binding — pure port, consumer contracts supply
   the digests.
3. **Richer evidence schema + lint/formatter pinning** (small,
   M1 follow-up): limits/trust visibility in the evidence schema,
   pinned lint+formatter in CI.
4. Then: author the **M4 spec** (enforced gate / trusted evaluation)
   to Accepted, per the spec-driven sequence.
