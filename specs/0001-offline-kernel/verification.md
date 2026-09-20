# Verification record

State: local and clean-checkout verification passed; remote CI gate blocked by
repository publication permissions.
This does not mark the broader M0/M1 milestones or Axiom v1 Verified.

## Local evidence — 2026-09-20 (T7: M1 read-only CLI)

Environment: Linux, Temurin OpenJDK 17.0.20+8, Clojure 1.12.0; 256 MiB JVM heap.
Dependencies are version- and SHA-256-pinned in `scripts/dependencies.lock`.

`./scripts/check` passed: 15 tests, 707 assertions, zero failures/errors;
scenario validation passed; separate CLI processes returned allow=0,
missing=3, stale=3, failed=2 (unchanged contract). Read-only gates:
`status`, `next` and `explain` returned exit 0 on the synthetic scenarios;
`explain --decision <id-from-evaluate>` reported `:explained? true` and
`explain --decision bogus` reported `:explained? false` with
`:reason :decision-id-mismatch`. `git diff --check` passed.

`evaluate` was refactored onto a shared pure `evaluate-all`; decision maps and
`:decision/id` digests for the four synthetic scenarios are byte-identical to
the pre-refactor implementation (verified by re-running `evaluate` on all
examples and comparing digests before/after — no drift).

T6 acceptance (R1–R7) re-ran green as part of this check: the full regression
corpus, strict-schema adversarial cases, determinism/replay invariants and the
four scenario exit codes all pass.

| Requirement | Test evidence |
| --- | --- |
| R1 | `bounded-data-reader`, `strict-schemas`, `malformed-field-types`, `path-safety` |
| R2 | `canonical-identity`, `candidate-mutation-invalidates-evidence`, generated map-order invariants |
| R3 | `replay-and-generated-invariants`, duplicate-event rejection |
| R4 | `admission-corpus`, `dependency-gates`, `path-safety` |
| R5 | `candidate-mutation-invalidates-evidence`, `reruns-and-conflicts`, `admission-corpus` |
| R6 | `cli-results` and four real CLI process exits in `scripts/check` |
| R7 | Above regression corpus, 100 generated claim/map-order cases; existing MIT license preserved |
| R8 | `status-report-shape`, `next-report-shape`, `explain-decision-shape`, `cli-read-commands`; `status`/`next`/`explain` process exits and decision-identity match/mismatch gates in `scripts/check` |

## Local evidence — 2026-09-20

Environment: Linux, OpenJDK 17.0.20+8, Clojure 1.12.0; 256 MiB JVM heap.
Dependencies are version- and SHA-256-pinned in `scripts/dependencies.lock`.

`./scripts/check` passed: 11 tests, 655 assertions, zero failures/errors;
scenario validation passed; separate CLI processes returned allow=0,
missing=3, stale=3 and failed=2. `git diff --check` passed.

The same command passed in a detached clean worktree of implementation commit
`06efed2`, with an empty dependency cache, confirming dependency downloads,
checksum verification, executable file modes and all CLI exit expectations.

## Publication blocker

Git push could not acquire an HTTPS credential. The connected GitHub API
rejected tree creation with HTTP 403, `Resource not accessible by integration`.
No branch or PR was published, and no remote CI run is claimed. The local
branch is `feat/offline-nomos-foundation`. Restore authorized repository write
access (including workflows for `.github/workflows/ci.yml`), publish the branch,
open a focused PR and inspect its CI before completing T6.

| Requirement | Test evidence |
| --- | --- |
| R1 | `bounded-data-reader`, `strict-schemas`, `malformed-field-types`, `path-safety` |
| R2 | `canonical-identity`, `candidate-mutation-invalidates-evidence`, generated map-order invariants |
| R3 | `replay-and-generated-invariants`, duplicate-event rejection |
| R4 | `admission-corpus`, `dependency-gates`, `path-safety` |
| R5 | `candidate-mutation-invalidates-evidence`, `reruns-and-conflicts`, `admission-corpus` |
| R6 | `cli-results` and four real CLI process exits in `scripts/check` |
| R7 | Above regression corpus, 100 generated claim/map-order cases; existing MIT license preserved |

## Limits and deferred work

- All evidence and producer identities are synthetic and unauthenticated.
- Source digest/approval provenance reconciliation is not implemented.
- The attempt counter is a synthetic per-producer/obligation/candidate counter;
  GitHub run IDs, matrices, artifact provenance and revocations are deferred.
- In-memory reduction only; no durability, migration, external observations,
  isolated worker, execution capability or merge enforcement.
- JSON, public schema export, status/next CLI and pinned lint/formatter remain
  follow-up slices. Tests are bounded observations, not formal correctness proof.
- Self-hosting is planned with independent evaluator/policy promotion gates;
  Axiom is not driving this implementation run.
