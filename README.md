# Axiom

Axiom is a symbolic supervision layer for coding agents. **Nomos** is its
deterministic rules subsystem: evaluate explicit requirements, repository
observations and test evidence before accepting completion claims.

The first implementation is an **offline advisory evaluator** in Clojure.
It validates supplied snapshots and returns allow, deny or defer with structured
reasons. It does not authenticate those snapshots, execute agents or authorize
merges. Test evidence is not a formal proof of correctness.

## Try it

On Linux with Java 17+, curl and sha256sum:

```sh
./scripts/check
./scripts/clj -m axiom.cli validate --input examples/synthetic-project/allow.edn
./scripts/clj -m axiom.cli evaluate --input examples/synthetic-project/allow.edn
./scripts/clj -m axiom.cli evaluate --input examples/synthetic-project/missing.edn
./scripts/clj -m axiom.cli status --input examples/synthetic-project/allow.edn
./scripts/clj -m axiom.cli next --input examples/synthetic-project/allow.edn
./scripts/clj -m axiom.cli explain --input examples/synthetic-project/allow.edn
```

The launcher downloads checksum-pinned Clojure jars to a local ignored cache.
The examples contain invented test-only producers and approvals. Their expected
evaluation exits are `allow: 0`, `missing: 3`, `stale: 3`, `failed: 2`.
Output is EDN; `:rules` explains each result and `:task-results` explains
dependency support. A validation exit of zero means well-formed input only.

Decisions can be recorded to a local SQLite ledger and replayed
deterministically:

```sh
# (ledgers are written by the runner; scripts/check builds a synthetic one)
./scripts/clj -m axiom.cli replay --ledger /tmp/axiom-ledger.db
./scripts/clj -m axiom.cli replay --ledger /tmp/axiom-ledger.db --through 1
./scripts/clj -m axiom.cli export-bundle --ledger /tmp/axiom-ledger.db --output /tmp/bundle.edn
./scripts/clj -m axiom.cli replay --bundle /tmp/bundle.edn
```

`replay` is read-only and never creates or migrates the ledger: exit 0 on a
valid replay, 4 on invalid input (missing file, bad `--through`, malformed
bundle), 5 on operational failure (tampered bundle, newer schema). Replay
recomputes every recorded decision and reports `:reproduced?`; the ledger is
hash-chained and snapshots are verified before use. The ledger is local and
single-process: it is tamper-evident, not tamper-proof, and inputs are not
authenticated.

Implemented: strict bounded EDN, canonical candidate identity, an in-memory
event reducer, accepted-spec/dependency/scope rules, candidate-bound evidence,
freshness/rerun checks and structured decisions. Claims never satisfy tests.
Read-only `status`/`next` report per-task decisions and eligible work; `explain`
returns a structured per-rule explanation with missing inputs, remediation
suggestions and decision-identity verification.

The goal is eventually to **use Axiom to drive its own development**, starting
with advisory self-evaluation and advancing to guarded agent execution. A
candidate must not replace the trusted evaluator or policy approving it.

- [Roadmap](docs/roadmap.md) and [self-hosting gates](docs/self-hosting.md)
- [Architecture](docs/architecture.md) and [offline protocol](docs/protocol.md)
- [Initial implementation spec](specs/0001-offline-kernel/requirements.md)
- [Full proposed design](docs/design-plan.md)
- [Contributor workflow](AGENTS.md)

Consumer-specific contracts, policies and integration evidence stay in their
own repositories. Licensed under the existing [MIT license](LICENSE).
