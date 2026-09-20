# M2 gate review — Durable local state

Reviewed: 2026-09-20. Authority: the owner's standing Axiom autopilot
instruction; this is a docs-only milestone audit, not an independent
security review. Source: the proposed design plan, "M2 — Durable ledger
and local observations" (Implement list + Gate). Candidate evidence:
specs `0002-durable-ledger` and `0003-local-observations`, both
**Verified** on main `afcda2f`.

Rule: this review audits the specs against the design's M2 criteria. It
does not edit any spec's Verified claims and does not claim anything
beyond M2 (no v1, no M0/M1 acceptance).

## Implement list audit

- **SQLite transactions, event uniqueness, schema migrations, replay,
  and rebuildable snapshots** — satisfied. Spec 0002 R1–R4: transactional
  sequence assignment, event-id/dedup-key rejection, forward-only schema
  migrations (v1→v2), hash-chained replay, snapshot replay-equivalence
  as a hard invariant. Acceptance gates: snapshot restore + replay
  equals full replay; migration preserves replay equivalence; newer
  schema fails operationally. Evidence: 0002 acceptance.md (31 tests,
  803 assertions, branch CI 35529057473, post-merge main CI 35529404498).
- **Local Git adapter for clean/dirty worktrees, base/head/tree
  identities, and full change enumeration** — satisfied. Spec 0003 R1:
  pure `axiom.git` port + `axiom.adapters.git` adapter; repeat-run
  identical identities verified against `git rev-parse` ground truth;
  dirty worktrees report head plus the dirty list with no synthetic tree
  SHA. Evidence: 0003 acceptance.md + verification.md (74 tests, 1280
  assertions, branch CI 35533256619, post-merge main CI 35533663951).
- **Artifact digesting and bounded retention metadata** — satisfied.
  Spec 0003 R3: sha256 digests matching `sha256sum`; retention bounds
  declared and enforced (exceeded bound → operational failure);
  expired/superseded rows retained for reproducibility; `artifacts`
  table, schema v3 forward-only migration. Evidence: 0003
  `artifacts_test`, check-gate digest probe.
- **Local diagnostic verification runner with timeout/cancellation;
  mark its evidence according to its actual trust level** — satisfied.
  Spec 0003 R4/R5: pure `axiom.runner` port + adapter; timeout,
  cancellation and output caps produce `:incomplete` results that
  cannot satisfy obligations; records marked
  `:trust/local-diagnostic`; structured argv, no shell interpolation.
  Evidence: 0003 acceptance.md, true-probe/sleep-probe check gates.
- **`replay` and decision export bundles** — satisfied. Spec 0002
  R6/R7: read-only `replay --ledger/--through` and `replay --bundle`;
  versioned content-addressed export bundles; bundle replay equals
  ledger replay (decision IDs identical). Exit contract 0/4/5.
  Evidence: 0002 acceptance.md + check gates.

## Gate audit (design's M2 gate conditions)

- **Duplicate and out-of-order observations are handled
  deterministically** — satisfied. Spec 0002 R2: duplicate event IDs
  and dedup keys rejected deterministically (`:duplicate` with
  reason); ingestion order assigns sequence; observed time never
  reorders committed events. Tested.
- **Interrupted writes do not create successful phantom actions** —
  satisfied. Spec 0002 R2: append is one transaction; a writer that did
  not see commit treats the append as unknown and re-reads rather than
  assuming success. Honest limit recorded in the spec: crash testing
  covers the append protocol, not SQLite internals.
- **Snapshots match replay** — satisfied. Spec 0002 R4: snapshot must
  equal a full replay of the covered events; acceptance gate tested on
  a generated 14-envelope stream; incompatible snapshots discarded and
  rebuilt.
- **Renames/deletions/symlinks/submodules are handled or explicitly
  blocked** — satisfied. Spec 0003 R2: path-safety classification;
  symlink escapes classified `:path/unsafe` and excluded from
  digestion (target bytes never enter the observation); submodules
  typed, not recursed; `..`-escapes fail operationally; renames,
  deletions, mode changes and Unicode paths enumerated exactly.
- **Local results cannot masquerade as trusted remote CI** —
  satisfied. Spec 0003 R5: observations and runner results marked
  `:trust/local-diagnostic`; trust-forgery rejected at validation and
  at the ledger write path (`record-observation` dispatches on
  `:observation/kind`); 0004 extends the same rule to
  `:trust/provider-observed`/`:trust/provider-authenticated` without
  touching the local mark.

## Honest limits (carried from the spec records)

- Spec verification evidence is bounded synthetic observation, not
  formal proof or a production trust attestation.
- The ledger is single-process SQLite; hash chaining is
  tamper-evident, not tamper-proof; inputs are unauthenticated.
- The runner has no worker isolation (that's M5); observations and
  runs authorize nothing — advisory only, no execution, merge, or
  enforcement.

## Verdict

**M2 is COMPLETE.** Every design-listed M2 deliverable and every M2
gate condition is satisfied by the Verified specs 0002 and 0003, with
evidence recorded in their verification records (local check +
branch CI + post-merge main CI).

Out of scope, deliberately not claimed: M0/M1 milestone acceptance
(their gate reviews were never performed; spec 0001's T6/T7 are
complete but the design's M1 gate — including the deferred items
source mappings/digests, richer evidence schemas, JSON interchange —
has not been reviewed), M3 milestone acceptance (spec 0004 is
Verified but its milestone gate review has not been done), and Axiom
v1 acceptance.

## Recommended next bounded units

1. **M3 milestone gate review** (docs-only, same pattern as this
   review): audit spec 0004 against the design's M3 Implement list and
   gate now that 0004 is Verified.
2. **M1 gate review** (docs-only): the design's M1 gate was never
   reviewed; this will surface the deferred M1 items (source
   mappings/digests, richer evidence schemas, JSON interchange,
   context/readiness commands) and decide whether M1 can be marked
   complete or needs additional bounded specs.
3. Only after M3's gate review: author the M4 spec
   (enforced gate / trusted evaluation) to Accepted, per the
   spec-driven sequence.
