# Verification record

## Spec acceptance evidence — 2026-09-20

This slice authored spec 0005 (M4 — enforced verification gate) to
Accepted. Docs-only: the change adds `specs/0005-enforced-gate/`
(requirements.md, design.md, tasks.md, verification.md,
acceptance.md) and updates the `docs/roadmap.md` M4 row. No changes
under `src/`, `test/` or `scripts/`.

Evidence:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **137 tests,
  1651 assertions, 0 failures, 0 errors** — exactly the 0004 baseline;
  all 0001–0004 CLI gates pass unchanged. This is the inertness
  baseline: the spec PR changes no code and no test counts.
- Spec acceptance gates in `acceptance.md` are all checked for the
  spec slice; implementation acceptance gates are recorded as pending.
- No HomeKV-specific content: the only HomeKV mentions are the
  standard repository-boundary statements ("nothing HomeKV-specific,
  never here"), consistent with prior specs. All fixtures referenced
  are synthetic (`synth-*`).
- `docs/roadmap.md`: M4 row updated to "Accepted (0005): enforced
  verification gate — trusted evaluation, approved-policy loading,
  bound check publication, protected branch/merge-queue integration,
  governance authorization separation; implementation next". The 0005
  scope is exactly the design's M4 item list, so the row update is
  honest.

Claims: nothing is claimed Verified by this slice; no M4 milestone
acceptance is claimed. Spec status is Accepted (2026-09-20);
implementation (tasks T1–T8) is pending.

## Slice 1 evidence — T1/T2 implemented (2026-09-20)

Branch `feat/0005-gate-port` (PR: see merge record below). This slice
implements tasks T1 and T2 only; T3–T8 remain open. Spec status
remains **Accepted** — nothing is claimed Verified, and no M4
milestone acceptance is claimed from this slice.

What landed:

- `src/axiom/gate.clj` — the pure `axiom.gate` port (T1). No I/O, no
  network, no database, no new production dependencies (requires only
  `clojure.string`). `evaluate` takes
  `(policy, observation, evaluator-id, capability)` and returns the
  decision map (`:gate/decision` ∈ `:allow`/`:deny`/`:defer`/
  `:invalid`, `:gate/reasons` with one named entry per required gate,
  `:gate/candidate`, `:gate/evaluator`, `:gate/policy` with digest
  plus approval event id, `:gate/trust`). Required gates match
  provider-assigned check-run IDs plus workflow identity — display
  titles are never consulted, so renamed checks do not satisfy
  (`:unknown` → defer). Stale runs (recorded base/head SHAs differ
  from the observation's) are `:stale` → deny. Omitted required
  verification is `:unknown` → defer; allow requires every required
  gate `:satisfied`. Candidate files under reserved policy paths deny
  with `:policy-path-touched-by-candidate`. Forged `:trust/remote-ci`
  claims (no evaluator-bound publication on a current observation)
  deny with `:forged-trust-mark`. `:trust/remote-ci` is issued only
  when the evaluator identity is the capability's configured trusted
  evaluator, the observation is a current trusted observation (and
  not a synthetic fixture — fixtures are structurally excluded via
  `:observation/synthetic-fixture?`), and the capability record
  shows `:enforcement` mode; earlier marks are passed through
  unchanged, never rewritten. Malformed required inputs (policy,
  observation, evaluator id, capability) and a run that cannot name
  its policy approval produce `:invalid` — never allow.
  `decision-for-unresolved` wires `axiom.policy` resolutions into
  defer/invalid decisions (unapproved digest → every gate `:defer`
  with `:no-approved-policy`).
- `src/axiom/policy.clj` — approved-policy loading (T2). Pure; no
  I/O. Content digests are SHA-256 over the 0001 canonical EDN
  encoding (reuses `axiom.model/digest`). `resolve` checks the digest
  against recorded `:governance/policy-approved` events (with
  `:supersedes` chains and revocation handling), structurally rejects
  any policy-source descriptor pointing at the candidate ref
  (`:policy-source-is-candidate-ref`), and returns `:deferred`
  (`:no-approved-policy`) for digests with no approval event —
  never an approved policy. `policy-approve-event` constructs the
  governance approval event shape (approver identity, digest,
  supersedes) used by later slices.
- `test/axiom/gate_test.clj`, `test/axiom/policy_test.clj` —
  adversarial unit tests: renamed checks, edited-policy candidates,
  omitted verifications, stale successes, forged `:trust/remote-ci`
  attempts (no publication / wrong evaluator), missing approval
  references, malformed inputs, candidate-branch policy sources,
  hostile display titles — asserting deny/defer/invalid with named
  reasons, never allow. Both namespaces registered in
  `test/axiom/test_runner.clj`. All fixtures synthetic (`synth-*`).
- `specs/0005-enforced-gate/tasks.md`: T1 and T2 marked `[x]`.

Evidence:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **167 tests,
  1784 assertions, 0 failures, 0 errors** — the 0004 baseline of 137
  tests / 1651 assertions plus 30 new tests / 133 new assertions for
  the gate and policy namespaces. All 0001–0004 CLI gates pass
  unchanged (exit contracts 0/4/5 verified end to end).
- No HomeKV-specific content: every fixture, repo, SHA, login and
  workflow in the new code and tests is invented (`synth-*`); no
  live credentials, no real repository identities, no network.
- No change to 0001–0004 decision bytes, exit contracts, or ledger
  schema semantics.

Honest limits:

- The `:observation/current?` / trust flags the issuance rule reads
  are attested by the adapters; the pure port trusts the validated
  input. Test fixtures set these flags explicitly, so the tests
  prove the predicate logic, not production trust.
- `:trust/remote-ci` ledger-envelope validation (forged marks
  `:invalid` at validation) and the `:decision/gate-evaluation` /
  `:governance/*` ledger kinds are T3, not this slice.
- The checks adapter, capability computation, and CLI are T4–T6;
  nothing in this slice touches the network or mutates provider
  state.
- Test evidence is bounded synthetic observation, not a formal proof
  and not a production trust attestation.

## Slice 2 evidence — T3 implemented (2026-09-20)

Branch `feat/0005-gate-ledger` (PR: see merge record below). This slice
implements task T3 only; T4–T8 remain open. Spec status remains
**Accepted** — nothing is claimed Verified, and no M4 milestone
acceptance is claimed from this slice.

What landed:

- `src/axiom/ledger.clj` — new payload kinds through the 0002 append
  path, additive to the existing schema:
  `:decision/gate-evaluation` (the verbatim `axiom.gate/evaluate`
  output map: `:gate/decision`, named `:gate/reasons`, `:gate/candidate`,
  `:gate/evaluator`, `:gate/policy` with digest plus approval-event
  reference, `:gate/trust`) and `:governance` (one of
  `:governance/policy-approved`, `:governance/policy-revoked`,
  `:governance/verifier-config-approved`,
  `:governance/protection-changed`, `:governance/admin-bypass`).
  `record-gate-decision` / `record-governance` are pure envelope
  constructors mirroring `record-observation` / `record-evidence`.
- Strict envelope validation extended for the new kinds (0003-style
  exact shapes plus per-kind rules):
  - a decision carrying `:trust/remote-ci` is `:invalid` unless it
    carries an evaluator-bound publication reference whose
    `:publication/evaluator` equals the decision's `:gate/evaluator`
    (no reference, wrong evaluator, and promotion from
    `:trust/local-diagnostic` are all rejected);
  - gate decisions require a non-blank evaluator identity and a
    policy header with a sha256 digest and a non-blank
    policy-approval reference — a run that cannot name its policy
    approval is `:invalid`, never recorded;
  - authorization-bearing governance kinds require an authorizer
    identity (`:governance/authorizer`, or `:governance/approver`
    for the `axiom.policy` approval-event shape) and a content
    digest; `:governance/admin-bypass` requires the actor and the
    reason; unknown kinds, unknown fields, and missing/mismatched
    role identities are `:invalid`, never normalized.
- 0002 invariants unchanged: transactional sequence, hash chain,
  event-id/dedup-key dedup (duplicates rejected deterministically as
  `:duplicate` / `:duplicate-event-id` / `:duplicate-dedup-key`),
  forward-only migrations — schema v4 adds one covering index
  (`idx_events_producer_seq`), additive only, never rewriting stored
  payloads.
- Replay: `replay-report` gains `:gate-decisions` (verbatim recorded
  decision with its policy digest, `:reproduced? true`) and
  `:governance` (verbatim events) sections. Replay-equivalence for
  gate decisions is byte-identical reproduction of the stored record —
  the decision is never re-derived from a different policy; integrity
  rests on the hash chain + payload digest verification that precedes
  the report. `extract-events` yields zero 0001 events for the new
  kinds, so 0001/0002 decision bytes and snapshot world digests are
  unchanged by their presence. Bundles carry the new kinds through
  the 0002 path unchanged.
- `src/axiom/store.clj` — migration 4; `supported-schema-version`
  3 → 4 (referenced from `axiom.ledger`).
- `test/axiom/gate_ledger_test.clj` — 9 new tests, 51 assertions,
  registered in `test/axiom/test_runner.clj`: gate-decision and
  governance round-trips verbatim through SQLite; replay of a prefix
  reproduces the recorded decision with its policy digest; forged
  `:trust/remote-ci` (no publication, wrong evaluator,
  promoted-from-`:trust/local-diagnostic`) rejected at both the pure
  constructor and the `store/append!` boundary with the ledger left
  untouched; governance events with missing authorizer/digest/actor/
  reason and unknown kinds → `:invalid`; decisions without evaluator
  identity or policy-approval reference → `:invalid`; duplicate
  governance events dedup via event-id and dedup-key; snapshot build/
  verify / restore equivalence with the new kinds present; new kinds
  contribute no 0001 world events; v3→v4 forward migration leaves
  payload digests byte-identical; bundle export/read round-trip with
  the new kinds. All fixtures synthetic (`synth-*`).
- `specs/0005-enforced-gate/tasks.md`: T3 marked `[x]`.

Evidence:

- `git diff --check` — clean (runs inside `./scripts/check`).
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **176 tests,
  1834 assertions, 0 failures, 0 errors** — the slice-1 baseline of
  167 tests / 1784 assertions plus 9 new tests / 51 new assertions,
  minus 1 redundant assertion removed from `artifacts_test.clj`
  (it asserted schema version 3 twice after the v4 bump; now asserts
  `store/supported-schema-version`). All 0001–0004 CLI gates pass
  unchanged (exit contracts 0/4/5 verified end to end).
- No HomeKV-specific content: every fixture, repo, SHA, login and
  workflow in the new code and tests is invented (`synth-*`); no
  live credentials, no real repository identities, no network.
- No change to 0001–0004 decision bytes, exit contracts, or ledger
  semantics beyond the additive payload kinds and the v4 index.

Honest limits:

- Replay `:reproduced? true` for gate decisions means the stored
  record is reproduced byte-identically; it is not a re-evaluation
  of the gate, and it proves nothing about the truth of the recorded
  decision — only that the ledger preserved it intact.
- The `:trust/remote-ci` ledger rule checks the *recorded*
  publication reference; whether the publication actually happened
  on the provider is T4 (checks adapter) territory, not proven here.
- Test evidence is bounded synthetic observation, not a formal proof
  and not a production trust attestation.
- The checks adapter, capability computation, and CLI are T4–T6;
  nothing in this slice touches the network or mutates provider
  state.

## Limits and deferred work

- Test evidence is bounded (synthetic fixtures), not a formal proof or
  production trust attestation.
- Agent execution, action dispatch, leases, the action outbox and
  isolated workers are M5; Axiom-driven self-hosting and its policy
  promotion gates are M5. Automatic merge is out of scope — an allow
  decision is not a merge instruction.
- If host enforcement cannot be configured, the implementation must
  report advisory mode instead of claiming enforcement (R8); external
  protection/permission changes require the repository owner's
  authorization.
- Approved-policy fixtures in this spec are synthetic and generic;
  real consumer policies live in consumer repositories.
