# Architecture and trust boundaries

The initial implementation is a single-process Clojure JVM CLI with a pure core.

| Module | Implemented responsibility |
| --- | --- |
| `axiom.model` | Versioned canonical encoding and candidate digest |
| `axiom.contract` | Bounded EDN reader, strict scenario/contract schemas, graph/path validation |
| `axiom.world` | In-memory event reduction; claims separated from evidence |
| `axiom.prover` | Synthetic test-result qualification and selected-attempt handling |
| `axiom.nomos` | Accepted spec, dependencies, scope, evidence and structured explanations |
| `axiom.cli` | Bounded file I/O, validation/evaluation commands, EDN and exit codes |

The CLI is a diagnostic tool over caller-supplied data. A producer ID or approval
reference in a file is not authenticated. All outputs explicitly identify
`offline-advisory` mode. The supervisor, authenticated adapters and protected
publication path in the proposed design are not implemented.

Normative requirements come from the supplied contract/policy; observations from
the supplied event/change snapshot; claims are retained but do not support rules.
Future adapters must authenticate observations and load approved policy outside
candidate control before any enforcement claim is justified.

Deterministic evaluation includes explicit time, world revision, input digest,
engine version, candidate identity and rule support references. Persistence and
cross-version replay require the M2 ledger; the current replay is in-memory.
The `prover` module evaluates test evidence; it does not establish formal proof.
