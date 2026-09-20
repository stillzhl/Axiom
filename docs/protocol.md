# Offline protocol v1

The checked-in scenario files are executable examples of this initial subset.
The full public protocol in the design plan is not yet implemented.

Input fields are strict: `:schema/version`, `:contract`, `:policy`, `:candidate`,
`:events`, `:changes`, `:task`, `:now`. Every field is required. Unknown keys,
unknown enum values and unsupported input versions are invalid, not ignored.
The implementation schema is in `axiom.contract`. IDs are bounded strings;
keywords are field names and fixed states. JSON interchange is deferred.

- Contracts contain specs, tasks and nonempty test-suite obligations. Task scope
  is a set of normalized directory prefixes, not general globs.
- Spec revision digests are supplied declarations in this slice; reconciliation
  against actual Markdown/source bytes is not implemented. Approval references
  are likewise unauthenticated. Do not use this subset for production governance.
- Candidates bind repository, base, head, tested commit/tree, contract, policy
  and recipe digests. Contract/policy hashes use `axiom.model/digest`.
- Events are ordered, uniquely identified claims or evidence records. Evidence
  holds the complete candidate, obligation, producer, suite, profile, recipe,
  monotonic synthetic attempt, result and observed epoch second.
- Changes explicitly declare completeness and old/new paths and modes. Both
  sides of renames count. Unsupported modes defer; invalid paths are errors.
- Evaluation time is an explicit epoch second. An age equal to the policy limit
  remains fresh; future-dated evidence is unknown.

Decisions carry `:allow`, `:deny` or `:defer`, named rules with
`:satisfied`/`:violated`/`:unknown`, reasons and support references. Dependency
support refers to task IDs in `:task-results`; evidence support refers to event
IDs. Unrelated task results are diagnostic and do not block the selected task.
No decision authorizes side effects. The original snapshot plus engine version
is required to reproduce a decision; the digest alone is not a replay bundle.

Canonical v1 encodes types explicitly, recursively sorts map keys/set elements,
preserves vector order, and hashes UTF-8 with SHA-256. It accepts only nil,
booleans, signed 64-bit integers, strings, keywords, maps, sets and vectors.
Limits are 256 KiB UTF-8 input, 32 nesting levels and 20,000 data nodes.
Unknown EDN tags, dispatch forms, reader evaluation and trailing values are
rejected. Direct JVM API input is trusted for allocation purposes and bounded
structurally before evaluation. These limits do not sandbox arbitrary code.

| Exit | Meaning |
| --- | --- |
| 0 | Valid input (`validate`) or advisory allow (`evaluate`) |
| 2 | Deny: at least one mandatory rule violated |
| 3 | Defer: unknown required input, with no violated rule |
| 4 | Invalid input or usage; no admission decision |
| 5 | File I/O operational failure |

Machine output is one EDN value on stdout. Launcher download/runtime diagnostics
go to stderr. The runtime launcher requires Java 17+, curl and sha256sum on
Linux; development/CI targets Java 17.0.20. Clojure CLI users can run
`clojure -M -m axiom.cli ...` or `clojure -M:test` using `deps.edn`.
