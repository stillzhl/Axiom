# Design

Use Clojure 1.12.0 on Java 17. No production dependencies beyond Clojure.
`axiom.model` owns a restricted EDN vocabulary and canonical SHA-256 encoding.
`axiom.contract` owns bounded reading and strict schemas. `axiom.world` reduces
validated events without I/O. `axiom.prover` qualifies evidence. `axiom.nomos`
combines rules and dependency decisions. `axiom.cli` handles files and exits.

Input is a version-1 scenario containing contract, policy, candidate, ordered
events, change observations, task ID and integer evaluation time (epoch seconds).
Contract and policy digests must match canonical content. A policy declares a
set of accepted lifecycle states, approved synthetic producers, a recipe digest
and maximum evidence age. Governing policy is supplied independently by the
caller; this CLI cannot establish its authority.

Canonical encoding v1 uses tagged vectors for each type, sorted encodings for
map keys and set values, and UTF-8 SHA-256. Supported values: nil, boolean,
signed 64-bit integer, string, keyword, vector, set, map. Lists, floats, ratios,
symbols, characters, tags and arbitrary objects are rejected. Input limits:
256 KiB, depth 32, 20,000 total nodes. A lexical pass bounds nesting and rejects
tags before invoking clojure.edn; the reader never evaluates code. Resource
limits here bound input structure, not a general-purpose process sandbox.

Scope consists of repository-relative directory prefixes ending in `/`; every
old/new path must be within the task's declared prefixes. Reject absolute,
empty/dot/dot-dot segments, backslash and control characters. Observations must
explicitly declare completeness. Dependency tasks are evaluated for the same
candidate and evidence but without reapplying the target task's changed-file
set to each dependency. All task gates have at least one evidence obligation.

## Read-only reporting (R8)

`axiom.nomos` keeps all reporting pure. `evaluate` is refactored onto an
internal `evaluate-all` that validates the scenario once and returns the
replayed state plus per-task `{:result :rules}` entries in dependency order
(without the target task's scope rule, which is added by `evaluate` exactly
as before, so decision bytes are unchanged).

- `(ready-tasks contract task-results)` returns, per contract task in id
  order, `:eligible?` (every declared dependency evaluated to `:allow`),
  the task's own result, `:unmet-dependencies` and
  `:missing-prerequisites` (the `:reason`s of its own `:unknown` rules).
  It does not consult wall-clock time, the network or the filesystem.
- `(explain-decision decision)` and `(explain-decision decision expected-id)`
  return a structured explanation: decision identity, mode, result, candidate
  id, task, world revision, input digest, evaluated-at, limitations, and per
  rule the rule id/version, subject, status, reason, support, plus derived
  `:missing` inputs and a `:remediation` suggestion keyed from the
  `[rule reason]` pair (nil for satisfied rules). When `expected-id` is
  supplied and differs from the recomputed decision id, the explanation
  reports `:explained? false` with `:reason :decision-id-mismatch` instead
  of narrating a stale record.
- `status-report`/`next-report` build the `status`/`next` payloads from the
  same pure functions. `status` evaluates each task with its own scope rule
  against the scenario's change observations and reports rule counts plus
  non-satisfied rules as blockers. `next` splits `ready-tasks` into
  `:eligible` and `:waiting`.

`axiom.cli` stays a thin adapter: argument parsing, file reading via the
existing bounded reader, and exit codes. Read-only commands return 0 when a
valid report was produced. Malformed arguments, unreadable files and invalid
scenarios keep exits 4/5. `explain` accepts an optional `--decision` id.

Evidence selection first matches obligation, the complete candidate and an
approved producer. Select the highest attempt per producer, then qualify its
recipe/suite/profile; never fall back to an older passing recipe. Multiple
approved producers disagreeing produce unknown. Any selected
record outside the freshness interval produces unknown. A selected failed
result violates; a selected pass satisfies; other outcomes are unknown.
Attempts are synthetic monotonic obligation/producer/candidate counters, not
GitHub run IDs. Provider run/matrix semantics belong to a future adapter.
