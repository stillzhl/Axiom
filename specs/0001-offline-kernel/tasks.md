# Tasks and acceptance gates

- [x] T1: Pin runtime and create executable test/check commands and CI.
- [x] T2: Implement R1 strict bounded parsing and schema validation.
- [x] T3: Implement R2 canonical candidate identity and R3 event reduction.
- [x] T4: Implement R4–R5 evidence qualification and Nomos rules.
- [x] T5: Implement R6 CLI and synthetic scenarios.
- [x] T6: Verify R1–R7; record exact commands, results and limitations.
  (Verified 2026-09-20: `./scripts/check` green locally — 15 tests, 707
  assertions, 0 failures; CLI exits allow 0 / missing 3 / stale 3 / failed 2;
  remote CI run on the T7 branch green; evidence in verification.md.)
- [x] T7: Implement R8 read-only CLI (`status`, `next`, `explain`) on the
  pure `ready-tasks`/`explain-decision` functions; keep `evaluate` decision
  bytes unchanged; cover with unit and CLI tests plus `scripts/check` gates.
  (Done 2026-09-20: merged as PR #2, `a9f10d3`. Decision digests for all
  four synthetic scenarios byte-identical to the pre-refactor implementation.)

Acceptance commands: `./scripts/check`, `./scripts/clj -m axiom.cli validate
--input examples/synthetic-project/allow.edn`, and all four scenario evaluations.
Expected evaluate exits: allow 0, missing 3, stale 3, failed 2.
T7 acceptance: `status`, `next` and `explain` return exit 0 on the synthetic
scenarios; `explain --decision <id-from-evaluate>` reports `:explained? true`
for the matching decision and `:explained? false` with
`:reason :decision-id-mismatch` for a bogus id; `next` marks a task whose
dependency is deferred as waiting with the unmet dependency named.
CI must run the same checks from a clean checkout. A green test run supplies
bounded test evidence, not a formal proof or production trust attestation.
